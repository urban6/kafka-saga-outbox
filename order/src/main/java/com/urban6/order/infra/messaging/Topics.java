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
	 * DLT 접미사. 프레임워크 기본값에 맡기지 않고 우리가 고정한다.
	 *
	 * spring-kafka 4 의 기본값은 -dlt 이고 그전엔 .DLT 였다 — 즉 버전을 올리면
	 * 토픽 이름이 조용히 바뀐다. DLT 이름은 운영이 알고 있어야 하는 계약이라 그런 변경이 나면 안 된다.
	 * 대문자로 두는 건 도메인 토픽이 아니라는 게 목록에서 한눈에 보이게 하려는 것이다.
	 */
	public static final String DLT_SUFFIX = ".DLT";

	/**
	 * ORDER_SAGA_REPLIES 의 DLT. 재시도를 소진했거나 역직렬화가 불가능했던 회신이
	 * 원본 그대로 남는다.
	 */
	public static final String ORDER_SAGA_REPLIES_DLT = ORDER_SAGA_REPLIES + DLT_SUFFIX;

	private Topics() {
	}
}
