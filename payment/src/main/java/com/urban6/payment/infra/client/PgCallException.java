package com.urban6.payment.infra.client;

/**
 * PG 가 에러 본문으로 거절한 호출 중 결론으로 번역할 수 없는 것.
 * 청구는 거절도 하나의 결론이라 결과 record 가 되지만, 빌링키 발급은 번역할 결론이 없다.
 */
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
