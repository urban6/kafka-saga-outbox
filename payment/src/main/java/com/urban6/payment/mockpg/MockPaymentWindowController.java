package com.urban6.payment.mockpg;

import com.urban6.payment.mockpg.MockPgEngine.PgPayment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 결제창(브라우저) 대역. <b>실제 Toss 서버 API 가 아니다</b> — 그래서 {@code /v1/payments} 밖에 둔다.
 * <p>
 * 실제 흐름에선 프론트가 SDK 로 결제창을 열고, 사용자가 카드사 인증을 마치면 Toss 가
 * {@code paymentKey / orderId / amount} 를 쿼리 파라미터로 붙여 {@code successUrl} 로 보낸다.
 * 이 프로젝트엔 브라우저가 없으므로 이 엔드포인트가 그 결과를 응답 본문으로 돌려준다.
 * 호출하는 쪽이 곧 "프론트엔드" 이고, 받은 값을 order 의 confirm API 에 넘기는 것까지가 그 역할이다.
 */
@RestController
@RequestMapping("/mock/window")
public class MockPaymentWindowController {

	public record AuthenticateRequest(
			@NotBlank String orderId,
			@NotNull @Positive BigDecimal amount
	) {
	}

	private final MockPgEngine engine;

	public MockPaymentWindowController(MockPgEngine engine) {
		this.engine = engine;
	}

	@PostMapping("/authenticate")
	public PgPayment authenticate(@Valid @RequestBody AuthenticateRequest request) {
		return engine.authenticate(request.orderId(), request.amount());
	}

	/** {@link MockPgController} 와 같은 이유의 로컬 핸들러. 응답 형식도 같다. */
	@ExceptionHandler(PgApiException.class)
	public ResponseEntity<MockPgController.ErrorResponse> handlePgError(PgApiException e) {
		return ResponseEntity.status(e.getStatus())
				.body(new MockPgController.ErrorResponse(e.getCode(), e.getMessage()));
	}
}
