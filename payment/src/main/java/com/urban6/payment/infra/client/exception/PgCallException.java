package com.urban6.payment.infra.client.exception;

/** 결론으로 번역할 수 없는 PG 에러(빌링키 발급 등). 청구는 결과 record 로 번역된다. */
public class PgCallException extends RuntimeException {

	private final int httpStatus;
	private final String code;

	public PgCallException(int httpStatus, String code, String message) {
		super(message);
		this.httpStatus = httpStatus;
		this.code = code;
	}

	public int getHttpStatus() {
		return httpStatus;
	}

	public String getCode() {
		return code;
	}
}
