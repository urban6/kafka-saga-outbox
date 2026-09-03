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
 * Toss 빌링(자동결제) API 모방. 카드 정보를 받아 키를 내는 건 실제 Toss 에도 있는
 * 서버 API 라 가짜 경로 없이 /v1 안에 둔다. 호출은 MockPgController 와 같이 실제 HTTP 다.
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

	/** @param idempotencyKey 이 Mock 은 orderId 로 중복을 판정하므로 헤더가 오는지 확인하는 용도다 */
	@PostMapping("/{billingKey}")
	public PgPayment charge(
			@PathVariable String billingKey,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody ChargeRequest request) {
		return engine.charge(billingKey, request.customerKey(), request.orderId(), request.amount());
	}

	/** MockPgController 와 같은 이유의 로컬 핸들러. 응답 형식도 같다. */
	@ExceptionHandler(PgApiException.class)
	public ResponseEntity<MockPgController.ErrorResponse> handlePgError(PgApiException e) {
		return ResponseEntity.status(e.getStatus())
				.body(new MockPgController.ErrorResponse(e.getCode(), e.getMessage()));
	}
}
