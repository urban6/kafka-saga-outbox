package com.urban6.payment.mockpg;

import com.urban6.payment.mockpg.MockPgEngine.PgPayment;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 같은 앱이지만 payment 는 실제 HTTP 로 부른다 — 빈 주입으로는 타임아웃이 재현되지 않는다. */
@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
public class MockPgController {

	public record ErrorResponse(String code, String message) {
	}

	private final MockPgEngine engine;

	@GetMapping("/orders/{orderId}")
	public PgPayment findByOrderId(@PathVariable String orderId) {
		return engine.findByOrderId(orderId);
	}

	/** 로컬 핸들러가 @RestControllerAdvice 보다 우선해 PG 응답 형식이 오염되지 않는다. */
	@ExceptionHandler(PgApiException.class)
	public ResponseEntity<ErrorResponse> handlePgError(PgApiException e) {
		return ResponseEntity.status(e.getStatus())
				.body(new ErrorResponse(e.getCode(), e.getMessage()));
	}
}
