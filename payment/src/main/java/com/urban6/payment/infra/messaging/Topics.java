package com.urban6.payment.infra.messaging;

/** 토픽 이름 상수. 파티션 키는 전부 orderNo. */
public final class Topics {

	/** Order → Payment. 수신 */
	public static final String PAYMENT_COMMANDS = "payment.commands";

	/** Payment → Order. 발행 */
	public static final String ORDER_SAGA_REPLIES = "order.saga.replies";

	private Topics() {
	}
}
