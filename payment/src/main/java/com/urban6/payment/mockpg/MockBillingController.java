package com.urban6.payment.mockpg;

import com.urban6.payment.mockpg.MockPgEngine.BillingKey;
import com.urban6.payment.mockpg.MockPgEngine.PgPayment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Toss 빌링(자동결제) API 모방.
 * <p>
 * 카드 정보를 직접 받아 빌링키를 내는 건 실제 Toss 에도 있는 서버 API 다(심사 필요).
 * 그래서 결제창 대역 같은 가짜 경로 없이 {@code /v1} 안에 둔다.
 * 같은 애플리케이션 안에 있지만 payment 서비스는 이걸 <b>실제 HTTP 로</b> 호출한다 —
 * {@link MockPgController} 와 같은 이유다.
 */
@RestController
@RequestMapping("/v1/billing")
@RequiredArgsConstructor
public class MockBillingController {

	public record IssueRequest(
			@NotBlank String customerKey,
			@NotBlank @Pattern(regexp = "\\d{16}") String cardNumber
	) {
	}

	/** Toss 응답에서 이 프로젝트가 읽는 필드만. 카드번호 전체는 절대 돌려주지 않는다. */
	public record IssueResponse(
			String billingKey,
			String customerKey,
			String cardLast4,
			Instant authenticatedAt
	) {
		static IssueResponse from(BillingKey key) {
			return new IssueResponse(key.billingKey(), key.customerKey(), key.cardLast4(), key.authenticatedAt());
		}
	}

	public record ChargeRequest(
			@NotBlank String customerKey,
			@NotBlank String orderId,
			@NotBlank String orderName,
			@NotNull @Positive BigDecimal amount
	) {
	}

	private final MockPgEngine engine;

	@PostMapping("/authorizations/card")
	public IssueResponse issue(@Valid @RequestBody IssueRequest request) {
		return IssueResponse.from(engine.issueBillingKey(request.customerKey(), request.cardNumber()));
	}

	/**
	 * @param idempotencyKey 클라이언트가 보내는 멱등키({@code order_no}). 이 Mock 은 {@code orderId} 로
	 *                       중복을 판정하므로 실제로 쓰지는 않고, 헤더가 오는지 확인하는 용도다.
	 */
	@PostMapping("/{billingKey}")
	public PgPayment charge(
			@PathVariable String billingKey,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody ChargeRequest request) {
		return engine.charge(billingKey, request.customerKey(), request.orderId(), request.amount());
	}

	/** {@link MockPgController} 와 같은 이유의 로컬 핸들러. 응답 형식도 같다. */
	@ExceptionHandler(PgApiException.class)
	public ResponseEntity<MockPgController.ErrorResponse> handlePgError(PgApiException e) {
		return ResponseEntity.status(e.getStatus())
				.body(new MockPgController.ErrorResponse(e.getCode(), e.getMessage()));
	}
}
