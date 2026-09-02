package com.urban6.inventory.infra.messaging;

/**
 * 이 서비스가 쓰는 토픽 이름.
 * <p>
 * order 의 {@code Topics} 와 값이 같지만 복사본이 아니라 각자의 소유다. inventory 는 자기가
 * 구독하고 발행하는 두 개만 안다.
 */
public final class Topics {

	/** 수신. Order → Inventory. 키: orderNo */
	public static final String INVENTORY_COMMANDS = "inventory.commands";

	/** 발행. Inventory → Order. 키: orderNo */
	public static final String ORDER_SAGA_REPLIES = "order.saga.replies";

	private Topics() {
	}
}
