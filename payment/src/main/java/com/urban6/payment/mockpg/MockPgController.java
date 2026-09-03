package com.urban6.payment.mockpg;

import com.urban6.payment.mockpg.MockPgEngine.PgPayment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Toss Payments 스펙을 모방한 Mock PG — 조회와 에러 응답 형식.
 * <p>
 * 같은 애플리케이션 안에 있지만 payment 서비스는 이걸 <b>실제 HTTP 로</b> 호출한다.
 * 빈을 직접 주입해 부르면 코드는 짧아지지만 연결 타임아웃·읽기 타임아웃·소켓 끊김이
 * 재현되지 않아서 방어 코드를 검증할 수 없다.
 * <p>
 * 청구는 {@link MockBillingController}({@code /v1/billing}) 에 있다. 여기 조회 API 는
 * 타임아웃 뒤 in-doubt 를 푸는 경로이고, {@code ALREADY_PROCESSED_PAYMENT} 를 받았을 때
 * PG 가 들고 있는 진짜 {@code paymentKey} 를 가져오는 경로다.
 */
@RestController
@RequestMapping("/v1/payments")
public class MockPgController {

	/** Toss 에러 응답 본문. 코드가 곧 재시도 여부의 판단 근거다. */
	public record ErrorResponse(String code, String message) {
	}

	private final MockPgEngine engine;

	public MockPgController(MockPgEngine engine) {
		this.engine = engine;
	}

	@GetMapping("/orders/{orderId}")
	public PgPayment findByOrderId(@PathVariable String orderId) {
		return engine.findByOrderId(orderId);
	}

	/**
	 * 컨트롤러 로컬 핸들러다. payment 서비스에 {@code @RestControllerAdvice} 가 생겨도
	 * 로컬 {@code @ExceptionHandler} 가 우선하므로 PG 응답 형식이 오염되지 않는다.
	 */
	@ExceptionHandler(PgApiException.class)
	public ResponseEntity<ErrorResponse> handlePgError(PgApiException e) {
		return ResponseEntity.status(e.getStatus())
				.body(new ErrorResponse(e.getCode(), e.getMessage()));
	}
}
