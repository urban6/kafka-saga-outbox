package com.urban6.payment.mockpg;

import org.springframework.http.HttpStatus;

public class PgApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	public PgApiException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}
}
