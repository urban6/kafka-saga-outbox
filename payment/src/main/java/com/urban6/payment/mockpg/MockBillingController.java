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

@RestController
@RequestMapping("/v1/billing")
@RequiredArgsConstructor
public class MockBillingController {

	public record IssueRequest(
			@NotBlank String customerKey,
			@NotBlank @Pattern(regexp = "\\d{16}") String cardNumber
	) {
	}

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

	/** @param idempotencyKey 이 Mock 은 orderId 로 중복을 판정하므로 헤더 존재 확인용이다 */
	@PostMapping("/{billingKey}")
	public PgPayment charge(
			@PathVariable String billingKey,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody ChargeRequest request) {
		return engine.charge(billingKey, request.customerKey(), request.orderId(), request.amount());
	}

	/** MockPgController 와 같은 이유의 로컬 핸들러. */
	@ExceptionHandler(PgApiException.class)
	public ResponseEntity<MockPgController.ErrorResponse> handlePgError(PgApiException e) {
		return ResponseEntity.status(e.getStatus())
				.body(new MockPgController.ErrorResponse(e.getCode(), e.getMessage()));
	}
}
