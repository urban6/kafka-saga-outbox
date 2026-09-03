package com.urban6.payment.infra.client;

/**
 * PG 가 에러 본문({@code {"code","message"}})으로 거절한 호출 중 <b>결론으로 번역할 수 없는 것</b>.
 * <p>
 * 청구는 결과 record 로 번역된다 — 거절도 하나의 결론이라 예외가 아니다.
 * 빌링키 발급처럼 동기 HTTP 로 되돌려줄 뿐인 호출은 번역할 결론이 없으므로 코드를 실어 던진다.
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
