package com.urban6.payment.api;

import com.urban6.payment.infra.client.PgCallException;
import com.urban6.payment.infra.client.PgRetryableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

/**
 * payment API 의 에러 응답을 한 형식으로 모은다.
 *
 * basePackageClasses 로 범위를 좁힌 것이 핵심이다. 범위를 안 주면 이 어드바이스가
 * mockpg 컨트롤러까지 잡아 Toss 형식이어야 할 PG 응답을 우리 형식으로 덮어쓴다.
 * PG 는 외부 시스템이라 우리 에러 계약을 따르면 안 된다.
 */
@Slf4j
@RestControllerAdvice(basePackageClasses = PaymentController.class)
public class GlobalExceptionHandler {

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
	 *
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

	/**
	 * 일시적 실패다. 503 인 이유는 클라이언트에게 "다시 걸어라" 를 말해야 하기 때문이다 —
	 * 400 이면 요청을 고치려 들고, 502 면 포기한다.
	 *
	 * Kafka 경로에서는 이 예외가 리스너 밖으로 나가 컨테이너가 재시도한다.
	 * 같은 예외가 진입 경로에 따라 다르게 쓰이는 것이고, 그게 의도다.
	 */
	@ExceptionHandler(PgRetryableException.class)
	public ResponseEntity<ErrorResponse> handlePgRetryable(PgRetryableException e) {
		log.warn("pg call retryable. code={} message={}", e.getCode(), e.getMessage());

		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(ErrorResponse.of(e.getCode(), "PG 처리가 지연되고 있습니다. 잠시 후 다시 시도해주세요.", List.of()));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
		log.error("unhandled exception", e);

		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ErrorResponse.of("INTERNAL_ERROR", "서버 오류가 발생했습니다", List.of()));
	}
}
