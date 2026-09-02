package com.urban6.order.infra.messaging;

/**
 * 사가에서 오가는 메시지 종류.
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
	/** 결제는 승인됐는데 재고 확정이 불가능할 때만 쓰는 보상 커맨드. */
	CANCEL_PAYMENT(Topics.PAYMENT_COMMANDS),

	// ── Payment → Order (회신) ────────────────────────────
	PAYMENT_APPROVED(Topics.ORDER_SAGA_REPLIES),
	PAYMENT_REJECTED(Topics.ORDER_SAGA_REPLIES),
	PAYMENT_CANCELED(Topics.ORDER_SAGA_REPLIES),

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
}
