package com.urban6.payment.infra.messaging;

/** 토픽 이름 상수. 파티션 키는 전부 orderNo. */
public final class Topics {

	/** Order → Payment. 수신 */
	public static final String PAYMENT_COMMANDS = "payment.commands";

	/** Payment → Order. 발행 */
	public static final String ORDER_SAGA_REPLIES = "order.saga.replies";

	/**
	 * DLT 접미사. 프레임워크 기본값이 버전마다 달라(spring-kafka 4 는 -dlt, 그전엔 .DLT)
	 * 우리가 고정한다. 운영이 아는 계약이라 업그레이드가 조용히 바꾸면 안 된다.
	 */
	public static final String DLT_SUFFIX = ".DLT";

	/**
	 * PAYMENT_COMMANDS 의 DLT. PG 장애가 재시도 예산(2초 x 5)보다 오래가면 여기로 온다.
	 */
	public static final String PAYMENT_COMMANDS_DLT = PAYMENT_COMMANDS + DLT_SUFFIX;

	private Topics() {
	}
}
