package com.urban6.payment.infra.client;

import com.urban6.payment.infra.client.exception.PgCallException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;

/** Toss 에러 코드를 PgChargeResult 로 번역한다. 반드시 트랜잭션 밖에서 호출한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PgClient {

	private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

	/** orderName 은 필수인데 상품명을 모르므로 주문번호를 넣는다. */
	private record ChargeRequest(String customerKey, String orderId, String orderName, BigDecimal amount) {
	}

	private record IssueBillingKeyRequest(String customerKey, String cardNumber) {
	}

	private record PgPaymentResponse(String paymentKey, String orderId, String status) {
	}

	private record PgBillingKeyResponse(String billingKey, String customerKey, String cardLast4) {
	}

	private record PgErrorResponse(String code, String message) {
	}

	public record IssuedBillingKey(String billingKey, String cardLast4) {
	}

	private final RestClient restClient;

	/** 청구와 달리 결론으로 번역하지 않는다 — 사가 밖의 동기 HTTP 라 실패를 그대로 돌려준다. */
	public IssuedBillingKey issueBillingKey(String customerId, String cardNumber) {
		return restClient.post()
				.uri("/v1/billing/authorizations/card")
				.body(new IssueBillingKeyRequest(customerId, cardNumber))
				.exchange((request, response) -> {
					HttpStatusCode status = response.getStatusCode();
					if (status.is2xxSuccessful()) {
						PgBillingKeyResponse body = response.bodyTo(PgBillingKeyResponse.class);
						log.info("billing key issued. customerId={} cardLast4={}", customerId, body.cardLast4());
						return new IssuedBillingKey(body.billingKey(), body.cardLast4());
					}
					PgErrorResponse error = response.bodyTo(PgErrorResponse.class);
					String code = error == null ? "UNKNOWN_ERROR" : error.code();
					log.warn("billing key issue failed. customerId={} httpStatus={} code={}",
							customerId, status.value(), code);
					throw new PgCallException(status.value(), code,
							error == null ? status.toString() : error.message());
				});
	}

	/** 타임아웃은 거절이 아니다 — 응답만 유실됐으면 돈은 이미 빠졌으므로 IN_DOUBT 다. */
	public PgChargeResult charge(String orderNo, String billingKey, String customerId, BigDecimal amount) {
		try {
			return restClient.post()
					.uri("/v1/billing/{billingKey}", billingKey)
					.header(IDEMPOTENCY_KEY, orderNo)
					.body(new ChargeRequest(customerId, orderNo, orderNo, amount))
					.exchange((request, response) -> map(orderNo, response));
		} catch (ResourceAccessException e) {
			// read timeout 은 exchange 람다에 도달하지 않는다. 여기서 잡아야 한다.
			log.warn("pg charge did not answer. orderNo={} cause={}", orderNo, e.getMessage());
			return PgChargeResult.inDoubt("PG_TIMEOUT", "PG 응답을 받지 못했습니다. orderNo=" + orderNo);
		}
	}

	// exchange 를 쓰는 건 기본 에러 핸들링이 4xx/5xx 에서 던져 본문의 code 를 못 읽어서다.
	private PgChargeResult map(String orderNo,
			RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) throws IOException {

		HttpStatusCode status = response.getStatusCode();
		if (status.is2xxSuccessful()) {
			PgPaymentResponse body = response.bodyTo(PgPaymentResponse.class);
			return PgChargeResult.approved(body.paymentKey());
		}

		PgErrorResponse error = response.bodyTo(PgErrorResponse.class);
		String code = error == null ? "UNKNOWN_ERROR" : error.code();
		String message = error == null ? status.toString() : error.message();
		log.info("pg charge error. orderNo={} httpStatus={} code={}", orderNo, status.value(), code);

		return switch (code) {
			// 실패가 아니라 성공이다(실패로 보면 받은 돈에 보상이 돈다). 응답에 paymentKey 가 없어 조회로 가져온다.
			case "ALREADY_PROCESSED_PAYMENT" -> reconcile(orderNo);

			case "REJECT_CARD_COMPANY" -> PgChargeResult.rejected(code, message);

			// 카드 폐기·만료. 돈은 안 빠졌고 재시도해도 같다.
			case "NOT_FOUND_BILLING_KEY" -> PgChargeResult.rejected(code, message);

			case "IDEMPOTENT_REQUEST_PROCESSING" -> PgChargeResult.retryable(code, message);

			case "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING", "PROVIDER_ERROR" ->
					PgChargeResult.retryable(code, message);

			default -> {
				// 거절로 접으면 승인된 결제에 보상이 돌고, 재청구로 접으면 이중 결제다. 조회로 확인한다.
				log.warn("unmapped pg error code. orderNo={} httpStatus={} code={}",
						orderNo, status.value(), code);
				yield PgChargeResult.inDoubt(code, message);
			}
		};
	}

	/** 조회가 실패해도 던지지 않고 IN_DOUBT 로 올려보낸다. 복구 배치가 다시 물어본다. */
	public PgChargeResult reconcile(String orderNo) {
		try {
			return restClient.get()
					.uri("/v1/payments/orders/{orderId}", orderNo)
					.exchange((request, response) -> {
						HttpStatusCode status = response.getStatusCode();
						if (status.is2xxSuccessful()) {
							PgPaymentResponse body = response.bodyTo(PgPaymentResponse.class);
							log.info("pg lookup. orderNo={} pgStatus={} paymentKey={}",
									orderNo, body.status(), body.paymentKey());
							return switch (body.status()) {
								case "DONE" -> PgChargeResult.approved(body.paymentKey());
								case "ABORTED", "CANCELED", "EXPIRED" ->
										PgChargeResult.rejected("PG_" + body.status(),
												"PG 에서 종료된 결제 입니다. pgStatus=" + body.status());
								default -> PgChargeResult.inDoubt("PG_" + body.status(),
										"PG 가 아직 처리 중입니다. pgStatus=" + body.status());
							};
						}

						PgErrorResponse error = response.bodyTo(PgErrorResponse.class);
						String code = error == null ? "UNKNOWN_ERROR" : error.code();
						if ("NOT_FOUND_PAYMENT".equals(code)) {
							log.info("pg has no payment. orderNo={} -> retryable", orderNo);
							return PgChargeResult.retryable(code, "PG 에 결제 기록이 없습니다.");
						}

						log.warn("pg lookup failed. orderNo={} httpStatus={} code={}",
								orderNo, status.value(), code);
						return PgChargeResult.inDoubt(code, "결제 조회에 실패했습니다. orderNo=" + orderNo);
					});
		} catch (ResourceAccessException e) {
			log.warn("pg lookup did not answer. orderNo={} cause={}", orderNo, e.getMessage());
			return PgChargeResult.inDoubt("PG_TIMEOUT", "결제 조회 응답을 받지 못했습니다. orderNo=" + orderNo);
		}
	}
}
