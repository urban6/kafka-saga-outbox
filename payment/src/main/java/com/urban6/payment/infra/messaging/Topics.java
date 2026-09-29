package com.urban6.payment.infra.messaging;

public final class Topics {

	public static final String PAYMENT_COMMANDS = "payment.commands";

	public static final String ORDER_SAGA_REPLIES = "order.saga.replies";

	/** 프레임워크 기본값이 버전마다 달라(spring-kafka 4 는 -dlt) 우리가 고정한다. */
	public static final String DLT_SUFFIX = ".DLT";

	public static final String PAYMENT_COMMANDS_DLT = PAYMENT_COMMANDS + DLT_SUFFIX;

	private Topics() {
	}
}
