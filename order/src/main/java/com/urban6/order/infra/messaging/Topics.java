package com.urban6.order.infra.messaging;

public final class Topics {

	public static final String PAYMENT_COMMANDS = "payment.commands";

	public static final String ORDER_SAGA_REPLIES = "order.saga.replies";

	public static final String ORDER_EVENTS = "order.events";

	/** 프레임워크 기본값이 버전마다 달라(-dlt / .DLT) 우리가 고정한다. */
	public static final String DLT_SUFFIX = ".DLT";

	public static final String ORDER_SAGA_REPLIES_DLT = ORDER_SAGA_REPLIES + DLT_SUFFIX;

	private Topics() {
	}
}
