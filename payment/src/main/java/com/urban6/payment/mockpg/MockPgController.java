package com.urban6.payment.mockpg;

import com.urban6.payment.mockpg.MockPgEngine.PgPayment;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Toss 스펙을 모방한 Mock PG 의 조회 API. 청구는 MockBillingController 에 있다.
 *
 * 같은 앱 안에 있지만 payment 는 이걸 실제 HTTP 로 호출한다 — 빈을 직접 주입하면
 * 타임아웃과 소켓 끊김이 재현되지 않아 방어 코드를 검증할 수 없다.
 */
@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
public class MockPgController {

	/** Toss 에러 응답 본문. 코드가 곧 재시도 여부의 판단 근거다. */
	public record ErrorResponse(String code, String message) {
	}

	private final MockPgEngine engine;

	@GetMapping("/orders/{orderId}")
	public PgPayment findByOrderId(@PathVariable String orderId) {
		return engine.findByOrderId(orderId);
	}

	/**
	 * 컨트롤러 로컬 핸들러다. payment 서비스에 @RestControllerAdvice 가 생겨도
	 * 로컬 @ExceptionHandler 가 우선하므로 PG 응답 형식이 오염되지 않는다.
	 */
	@ExceptionHandler(PgApiException.class)
	public ResponseEntity<ErrorResponse> handlePgError(PgApiException e) {
		return ResponseEntity.status(e.getStatus())
				.body(new ErrorResponse(e.getCode(), e.getMessage()));
	}
}
