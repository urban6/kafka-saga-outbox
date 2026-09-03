package com.urban6.order.infra.messaging;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 사가에서 오가는 메시지 종류와 각자가 실릴 토픽.
 *
 * name() 이 그대로 outbox.event_type 과 카프카 헤더에 실리는 와이어 값이라 함부로 바꾸지 않는다.
 * 여기 없는 것(원격 보상·재고)은 기능이 없다는 뜻이다 — 값만 선언하면 있는 것처럼 읽힌다.
 */
public enum EventType {

	// ── Order → Payment (커맨드) ──────────────────────────
	APPROVE_PAYMENT(Topics.PAYMENT_COMMANDS),

	// ── Payment → Order (회신) ────────────────────────────
	PAYMENT_APPROVED(Topics.ORDER_SAGA_REPLIES),
	PAYMENT_REJECTED(Topics.ORDER_SAGA_REPLIES),

	// ── Order → External (도메인 이벤트) ──────────────────
	ORDER_COMPLETED(Topics.ORDER_EVENTS),
	ORDER_CANCELED(Topics.ORDER_EVENTS);

	private final String topic;

	EventType(String topic) {
		this.topic = topic;
	}

	public String topic() {
		return topic;
	}

	private static final Map<String, EventType> BY_WIRE_VALUE = Stream.of(values())
			.collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

	/**
	 * 와이어 문자열 → enum. 모르는 값이면 빈 Optional 이고 예외를 던지지 않는다.
	 * 이 변환이 여기 한 곳에만 있어야 프로듀서가 타입을 추가해도 컨슈머가 그대로 돈다.
	 */
	public static Optional<EventType> fromWire(String wireValue) {
		return Optional.ofNullable(wireValue).map(BY_WIRE_VALUE::get);
	}

	/** 회신 토픽으로 오는 타입인지. 자기가 발행하는 커맨드가 회신 토픽에 섞여 와도 걸러낸다. */
	public boolean isSagaReply() {
		return Topics.ORDER_SAGA_REPLIES.equals(topic);
	}
}
