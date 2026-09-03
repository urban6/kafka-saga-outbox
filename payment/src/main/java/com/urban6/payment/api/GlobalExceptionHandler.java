package com.urban6.payment.api;

import com.urban6.payment.infra.client.PgCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

/**
 * payment API 의 에러 응답을 한 형식으로 모은다.
 * <p>
 * <b>{@code basePackageClasses} 로 범위를 좁힌 것이 핵심이다.</b> 범위를 안 주면 이 어드바이스가
 * {@code mockpg} 컨트롤러까지 잡아 Toss 형식이어야 할 PG 응답을 우리 형식으로 덮어쓴다.
 * PG 는 외부 시스템이라 우리 에러 계약을 따르면 안 된다.
 */
@RestControllerAdvice(basePackageClasses = PaymentController.class)
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	public record ErrorResponse(String code, String message, List<String> details, Instant timestamp) {

		static ErrorResponse of(String code, String message, List<String> details) {
			return new ErrorResponse(code, message, details, Instant.now());
		}
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
		List<String> details = e.getBindingResult().getFieldErrors().stream()
				.map(error -> error.getField() + ": " + error.getDefaultMessage())
				.toList();

		return ResponseEntity.badRequest()
				.body(ErrorResponse.of("INVALID_REQUEST", "요청 값이 올바르지 않습니다", details));
	}

	/**
	 * PG 에러 응답을 그대로 노출하지 않고 우리 형식으로 감싼다. 코드는 그대로 — 원인 추적에 필요하다.
	 * <p>
	 * PG 의 4xx 는 우리 입력이 틀린 것(카드 정보)이라 400 으로, 5xx 는 PG 장애라 502 로 돌려준다.
	 * 우리 서버 문제(500)와 구분되어야 한다.
	 */
	@ExceptionHandler(PgCallException.class)
	public ResponseEntity<ErrorResponse> handlePgCall(PgCallException e) {
		log.warn("pg call failed. status={} code={} message={}", e.getHttpStatus(), e.getCode(), e.getMessage());

		HttpStatus status = e.getHttpStatus() >= 500 ? HttpStatus.BAD_GATEWAY : HttpStatus.BAD_REQUEST;
		return ResponseEntity.status(status)
				.body(ErrorResponse.of(e.getCode(), "PG 요청이 거절되었습니다: " + e.getMessage(), List.of()));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
		log.error("unhandled exception", e);

		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ErrorResponse.of("INTERNAL_ERROR", "서버 오류가 발생했습니다", List.of()));
	}
}
