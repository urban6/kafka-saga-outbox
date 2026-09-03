package com.urban6.order.infra.messaging;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 사가에서 오가는 메시지 종류.
 *
 * 원격 보상(CANCEL_PAYMENT/PAYMENT_CANCELED)은 없다. 이 사가의 pivot 은
 * PG 청구 성공 시점이고, 그 이후 실패는 보상이 아니라 재시도로 민다. 청구 전 실패는 로컬 보상
 * (재고 해제 + 주문 취소)으로 끝나 원격 커맨드가 필요 없다. 값만 선언해두면 없는 기능이
 * 있는 것처럼 읽히므로 두지 않는다 — 이건 와이어 계약이라 더더욱 그렇다.
 *
 * 이름(name)이 그대로 outbox.event_type / 카프카 헤더에 실리는 와이어 값이므로 상수를 함부로 바꾸지 않는다.
 * 각 타입은 발행될 토픽을 고정으로 가진다.
 *
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
	 * 와이어 문자열 → enum. 모르는 값이면 빈 Optional이고 예외를 던지지 않는다.
	 *
	 * payment 가 회신 종류를 추가해도 order 는 무시하고 다음 메시지로 간다.
	 * 이 변환이 여기 한 곳에만 있어야 그 성질이 유지된다.
	 *
	 * valueOf 를 쓰면 모르는 값마다 예외가 나고, DefaultErrorHandler 가
	 * 4회 시도(2초 간격) 뒤에야 그 메시지를 버린다. 영구히 막히진 않지만 그동안 같은 파티션의
	 * 뒷 메시지가 전부 밀린다 — 프로듀서가 새 타입을 밀기 시작하면 처리량이 그 배수로 주저앉는다.
	 * (실측: 통합 테스트에서 valueOf 로 바꿔도 다음 메시지는 결국 처리된다.
	 * 영구 차단은 역직렬화 실패 쪽이고, 그건 ErrorHandlingDeserializer 가 막는다)
	 */
	public static Optional<EventType> fromWire(String wireValue) {
		return Optional.ofNullable(wireValue).map(BY_WIRE_VALUE::get);
	}

	/** 회신 토픽으로 오는 타입인지. 자기가 발행하는 커맨드가 회신 토픽에 섞여 와도 걸러낸다. */
	public boolean isSagaReply() {
		return Topics.ORDER_SAGA_REPLIES.equals(topic);
	}
}
