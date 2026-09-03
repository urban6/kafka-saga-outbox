package com.urban6.order.infra.messaging;

/** 토픽 이름을 코드 한 곳에서만 쓰기 위한 상수 모음. 파티션 키는 전부 orderNo. */
public final class Topics {

	/** Order → Payment. 키: orderNo */
	public static final String PAYMENT_COMMANDS = "payment.commands";

	/** Payment → Order. 모든 단계 결과 회신. 키: orderNo */
	public static final String ORDER_SAGA_REPLIES = "order.saga.replies";

	/** Order → External. 키: orderNo */
	public static final String ORDER_EVENTS = "order.events";

	/**
	 * DLT 접미사. 프레임워크 기본값이 버전마다 달라(spring-kafka 4 는 -dlt, 그전엔 .DLT)
	 * 우리가 고정한다. 운영이 아는 계약이라 업그레이드가 조용히 바꾸면 안 된다.
	 */
	public static final String DLT_SUFFIX = ".DLT";

	/** ORDER_SAGA_REPLIES 의 DLT. 재시도를 소진했거나 역직렬화가 안 된 회신이 원본 그대로 남는다. */
	public static final String ORDER_SAGA_REPLIES_DLT = ORDER_SAGA_REPLIES + DLT_SUFFIX;

	private Topics() {
	}
}
