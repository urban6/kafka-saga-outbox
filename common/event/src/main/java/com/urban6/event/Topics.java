package com.urban6.event;

/** docs/topic.md 의 토픽 이름을 코드 한 곳에서만 쓰기 위한 상수 모음. */
public final class Topics {

	/** Order → Payment. 키: orderNo */
	public static final String PAYMENT_COMMANDS = "payment.commands";

	/** Order → Inventory. 키: orderNo */
	public static final String INVENTORY_COMMANDS = "inventory.commands";

	/** Payment / Inventory → Order. 모든 단계 결과 회신. 키: orderNo */
	public static final String ORDER_SAGA_REPLIES = "order.saga.replies";

	/** Order → External. 키: orderNo */
	public static final String ORDER_EVENTS = "order.events";

	private Topics() {
	}
}
