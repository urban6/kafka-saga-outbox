package com.urban6.payment.infra.client.exception;

/**
 * PG 가 "지금은 안 되지만 다시 하면 될 수 있다" 고 답한 경우.
 *
 * 결과가 아니라 예외인 이유는 재시도가 DB 에 아무것도 남기지 않아야 해서다 — 결제 행을 남기면
 * uk_order_no 가 다음 시도를 막고, 멱등 선점을 남기면 그 커맨드는 영영 재처리되지 않는다.
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
