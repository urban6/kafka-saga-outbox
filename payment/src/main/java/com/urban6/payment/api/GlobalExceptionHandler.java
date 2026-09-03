package com.urban6.payment.api;

import com.urban6.payment.infra.client.exception.PgCallException;
import com.urban6.payment.infra.client.exception.PgRetryableException;
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
 * basePackageClasses 로 범위를 좁혀야 한다 — 안 좁히면 mockpg 컨트롤러까지 잡아
 * Toss 형식이어야 할 PG 응답을 우리 형식으로 덮어쓴다.
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
	 * PG 에러를 우리 형식으로 감싼다. 코드는 그대로 둔다 — 원인 추적에 필요하다.
	 * PG 의 4xx 는 우리 입력이 틀린 것이라 400, 5xx 는 PG 장애라 502 다(우리 500 과 구분).
	 */
	@ExceptionHandler(PgCallException.class)
	public ResponseEntity<ErrorResponse> handlePgCall(PgCallException e) {
		log.warn("pg call failed. status={} code={} message={}", e.getHttpStatus(), e.getCode(), e.getMessage());

		HttpStatus status = e.getHttpStatus() >= 500 ? HttpStatus.BAD_GATEWAY : HttpStatus.BAD_REQUEST;
		return ResponseEntity.status(status)
				.body(ErrorResponse.of(e.getCode(), "PG 요청이 거절되었습니다: " + e.getMessage(), List.of()));
	}

	/**
	 * 일시적 실패라 503 이다 — 400 이면 클라이언트가 요청을 고치려 들고 502 면 포기한다.
	 * 같은 예외가 Kafka 경로에서는 리스너 밖으로 나가 컨테이너 재시도가 된다.
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
