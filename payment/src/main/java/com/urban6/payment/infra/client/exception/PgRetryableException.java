package com.urban6.payment.infra.client.exception;

/** 결과가 아니라 예외인 건 DB 에 아무것도 남기지 않아야 해서다 — 행이나 멱등 선점이 남으면 재시도가 막힌다. */
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
