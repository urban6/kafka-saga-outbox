package com.urban6.payment.mockpg;

import com.urban6.payment.mockpg.MockPgEngine.PgPayment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Toss Payments 스펙을 모방한 Mock PG.
 * <p>
 * 같은 애플리케이션 안에 있지만 payment 서비스는 이걸 <b>실제 HTTP 로</b> 호출한다.
 * 빈을 직접 주입해 부르면 코드는 짧아지지만 연결 타임아웃·읽기 타임아웃·소켓 끊김이
 * 재현되지 않아서 방어 코드를 검증할 수 없다.
 * <p>
 * 계약은 실제 Toss 서버 API 스펙을 따른다. 결제창(브라우저)은 {@link MockPaymentWindowController} 가
 * 대신하며, 거기서 발급된 {@code paymentKey} 가 여기 confirm 으로 돌아와야 승인된다.
 */
@RestController
@RequestMapping("/v1/payments")
public class MockPgController {

	public record ConfirmRequest(
			@NotBlank String paymentKey,
			@NotBlank String orderId,
			@NotNull @Positive BigDecimal amount
	) {
	}

	/** Toss 에러 응답 본문. 코드가 곧 재시도 여부의 판단 근거다. */
	public record ErrorResponse(String code, String message) {
	}

	private final MockPgEngine engine;

	public MockPgController(MockPgEngine engine) {
		this.engine = engine;
	}

	/**
	 * @param idempotencyKey 클라이언트가 보내는 멱등키({@code order_no}). 이 Mock 은 {@code orderId} 로
	 *                       중복을 판정하므로 실제로 쓰지는 않고, 헤더가 오는지 확인하는 용도다.
	 */
	@PostMapping("/confirm")
	public PgPayment confirm(
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody ConfirmRequest request) {

		return engine.confirm(request.paymentKey(), request.orderId(), request.amount());
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
