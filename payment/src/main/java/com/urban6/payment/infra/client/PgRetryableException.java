package com.urban6.payment.infra.client;

/**
 * PG 가 "지금은 안 되지만 다시 하면 될 수 있다" 고 답한 경우.
 * <p>
 * <b>결과가 아니라 예외인 이유:</b> 재시도는 DB 에 아무것도 남기지 않아야 한다.
 * 결제 행을 남기면 {@code uk_order_no} 가 다음 시도를 막고, 멱등 선점을 남기면
 * 그 커맨드는 영영 재처리되지 않는다. 아무것도 안 하려면 트랜잭션에 들어가기 전에 빠져나가야 하고,
 * 그러려면 예외여야 한다.
 * <p>
 * {@link PgCallException} 과 나눈 것은 <b>처방이 다르기 때문</b>이다.
 * 저쪽은 "이 요청은 틀렸다"(고쳐서 다시 오라), 이쪽은 "요청은 맞다, 나중에 오라" 다.
 */
public class PgRetryableException extends RuntimeException {

	private final String code;

	public PgRetryableException(String code, String message) {
		super(message);
		this.code = code;
	}

	public String getCode() {
		return code;
	}
}
