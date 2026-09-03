package com.urban6.order.infra.messaging;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 사가에서 오가는 메시지 종류.
 * <p>
 * <b>원격 보상({@code CANCEL_PAYMENT}/{@code PAYMENT_CANCELED})은 없다.</b> 이 사가의 pivot 은
 * PG 청구 성공 시점이고, 그 이후 실패는 보상이 아니라 재시도로 민다. 청구 전 실패는 로컬 보상
 * (재고 해제 + 주문 취소)으로 끝나 원격 커맨드가 필요 없다. 값만 선언해두면 <b>없는 기능이
 * 있는 것처럼</b> 읽히므로 두지 않는다 — 이건 와이어 계약이라 더더욱 그렇다.
 * <p>
 * 이름(name)이 그대로 outbox.event_type / 카프카 헤더에 실리는 와이어 값이므로 상수를 함부로 바꾸지 않는다.
 * 각 타입은 발행될 토픽을 고정으로 가진다.
 * <p>
 * 재고 관련 타입이 없는 이유: 재고가 order 안으로 들어와 로컬 트랜잭션이 됐다.
 * 자기 자신에게 커맨드를 보내지 않는다.
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
	 * 와이어 문자열 → enum. <b>모르는 값이면 빈 Optional</b>이고 예외를 던지지 않는다.
	 * <p>
	 * payment 가 회신 종류를 추가해도 order 는 무시하고 다음 메시지로 간다.
	 * 이 변환이 여기 한 곳에만 있어야 그 성질이 유지된다 — 다른 데서 {@code valueOf} 를 쓰면
	 * 거기서 파티션이 막힌다.
	 */
	public static Optional<EventType> fromWire(String wireValue) {
		return Optional.ofNullable(wireValue).map(BY_WIRE_VALUE::get);
	}

	/** 회신 토픽으로 오는 타입인지. 자기가 발행하는 커맨드가 회신 토픽에 섞여 와도 걸러낸다. */
	public boolean isSagaReply() {
		return Topics.ORDER_SAGA_REPLIES.equals(topic);
	}
}
