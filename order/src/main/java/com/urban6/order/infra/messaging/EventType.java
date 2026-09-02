package com.urban6.order.infra.messaging;

/**
 * 사가에서 오가는 메시지 종류.
 * <p>
 * 이름(name)이 그대로 outbox.event_type / 카프카 헤더에 실리는 와이어 값이므로 상수를 함부로 바꾸지 않는다.
 * 각 타입은 발행될 토픽을 고정으로 가진다.
 */
public enum EventType {

	// ── Order → Payment (커맨드) ──────────────────────────
	APPROVE_PAYMENT(Topics.PAYMENT_COMMANDS),
	CANCEL_PAYMENT(Topics.PAYMENT_COMMANDS),

	// ── Order → Inventory (커맨드) ────────────────────────
	RESERVE_STOCK(Topics.INVENTORY_COMMANDS),
	CONFIRM_STOCK(Topics.INVENTORY_COMMANDS),
	RELEASE_STOCK(Topics.INVENTORY_COMMANDS),

	// ── Payment → Order (회신) ────────────────────────────
	PAYMENT_APPROVED(Topics.ORDER_SAGA_REPLIES),
	PAYMENT_REJECTED(Topics.ORDER_SAGA_REPLIES),
	PAYMENT_CANCELED(Topics.ORDER_SAGA_REPLIES),

	// ── Inventory → Order (회신) ──────────────────────────
	STOCK_RESERVED(Topics.ORDER_SAGA_REPLIES),
	STOCK_REJECTED(Topics.ORDER_SAGA_REPLIES),
	STOCK_CONFIRMED(Topics.ORDER_SAGA_REPLIES),
	STOCK_RELEASED(Topics.ORDER_SAGA_REPLIES),

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
