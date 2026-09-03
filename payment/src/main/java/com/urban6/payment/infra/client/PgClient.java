package com.urban6.payment.infra.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Mock PG 호출.
 *
 * 이 클래스가 하는 일은 Toss 에러 코드를 우리 결론으로 번역하는 것 하나다.
 * 결제 도메인은 ALREADY_PROCESSED_PAYMENT 같은 문자열을 알 필요가 없다.
 *
 * 멱등키는 order_no 다. 같은 주문을 두 번 청구해도 PG 가 한 번만 처리한다.
 * 트랜잭션 밖에서 호출한다 — 외부 I/O 가 트랜잭션 안에 들어오면 PG 가 느려질 때
 * DB 커넥션이 그만큼 잡혀 있는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PgClient {

	private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

	/** Toss 빌링 청구 본문. orderName 은 필수인데 payment 는 상품명을 모르므로 주문번호를 넣는다. */
	private record ChargeRequest(String customerKey, String orderId, String orderName, BigDecimal amount) {
	}

	private record IssueBillingKeyRequest(String customerKey, String cardNumber) {
	}

	/** 응답 본문. 우리가 쓰는 필드만 선언한다 — PG 가 필드를 늘려도 안 깨진다. */
	private record PgPaymentResponse(String paymentKey, String orderId, String status) {
	}

	private record PgBillingKeyResponse(String billingKey, String customerKey, String cardLast4) {
	}

	private record PgErrorResponse(String code, String message) {
	}

	/** 빌링키 발급 결과. 카드번호는 돌아오지 않는다 — 끝 4자리는 PG 가 응답으로 준 값이다. */
	public record IssuedBillingKey(String billingKey, String cardLast4) {
	}

	private final RestClient restClient;

	/**
	 * Toss POST /v1/billing/authorizations/card. 카드번호는 이 호출로 우리 손을 떠난다.
	 *
	 * 청구와 달리 결론으로 번역하지 않는다. 발급은 사가 밖의 동기 HTTP 라 실패를 그대로 돌려주면 된다.
	 */
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

	/**
	 * Toss POST /v1/billing/{billingKey}. 여기서 돈이 빠진다.
	 *
	 * 빌링키는 URL 에, 고객 식별자는 본문에 간다. PG 는 둘이 같은 고객의 것인지 대조한다 —
	 * 그래서 다른 고객의 키로는 청구가 안 된다.
	 *
	 * 타임아웃은 거절이 아니다. 요청이 안 갔을 수도, 갔는데 응답만 유실됐을 수도 있다.
	 * 후자면 돈은 이미 빠졌다. 소켓이 끊긴 것과 결제가 실패한 것은 다른 사건이라
	 * IN_DOUBT 로 올려보내고 조회로 해소한다.
	 */
	public PgChargeResult charge(String orderNo, String billingKey, String customerId, BigDecimal amount) {
		try {
			return restClient.post()
					.uri("/v1/billing/{billingKey}", billingKey)
					.header(IDEMPOTENCY_KEY, orderNo)
					.body(new ChargeRequest(customerId, orderNo, orderNo, amount))
					.exchange((request, response) -> map(orderNo, response));
		} catch (ResourceAccessException e) {
			// 연결 실패·read timeout·소켓 끊김이 전부 여기로 온다. 응답 자체가 없었다는 뜻이다.
			log.warn("pg charge did not answer. orderNo={} cause={}", orderNo, e.getMessage());
			return PgChargeResult.inDoubt("PG_TIMEOUT", "PG 응답을 받지 못했습니다. orderNo=" + orderNo);
		}
	}

	/**
	 * exchange 를 쓰는 이유: 기본 에러 핸들링은 4xx/5xx 에 예외를 던져버려서
	 * 에러 본문의 code 를 읽을 수 없다. 우리에겐 그 코드가 판단 근거다.
	 */
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
			// 실패가 아니라 성공이다. 여기서 잘못 판단하면 이미 받은 돈에 대해
			// 재고 해제와 주문 취소가 돌아 최악이 된다.
			//
			// 이 응답에는 paymentKey 가 없고, 빌링은 결제창과 달리 우리가 보낸 키도 없다.
			// 실제 결제를 성사시킨 이전 시도의 키는 PG 만 안다 — 조회로 가져온다.
			case "ALREADY_PROCESSED_PAYMENT" -> reconcile(orderNo);

			case "REJECT_CARD_COMPANY" -> PgChargeResult.rejected(code, message);

			// DB 엔 키가 있는데 PG 가 모른다 — 카드 폐기·만료 상황이다. 돈은 안 빠졌고 재시도해도 같다.
			// (Mock 은 인메모리라 payment 앱을 재시작하면 이 경로가 실제로 나온다. 재등록으로 복구)
			case "NOT_FOUND_BILLING_KEY" -> PgChargeResult.rejected(code, message);

			// 같은 멱등키의 앞선 요청이 아직 처리 중이다. 그 요청이 끝나면 결과가 생기므로
			// 잠시 뒤 다시 부르면 승인이든 ALREADY_PROCESSED 든 답이 나온다.
			case "IDEMPOTENT_REQUEST_PROCESSING" -> PgChargeResult.retryable(code, message);

			// PG 가 "처리하지 못했다" 고 명시한 실패다. 돈이 안 빠진 게 PG 의 주장이므로 재청구가 안전하다.
			case "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING", "PROVIDER_ERROR" ->
					PgChargeResult.retryable(code, message);

			default -> {
				// 모르는 코드를 거절로 떨어뜨리면 승인된 결제에 보상이 돌 수 있다.
				// 그렇다고 재청구하면 이중 결제다 — 돈이 빠졌는지 모르기 때문이다.
				// 모를 때 안전한 행동은 하나뿐이다: 조회로 확인한다.
				log.warn("unmapped pg error code. orderNo={} httpStatus={} code={}",
						orderNo, status.value(), code);
				yield PgChargeResult.inDoubt(code, message);
			}
		};
	}

	/**
	 * 조회 API 로 PG 가 실제로 들고 있는 결제를 확인한다. 대사(reconciliation)의 유일한 경로다.
	 *
	 * 두 곳에서 쓴다 — ALREADY_PROCESSED_PAYMENT 응답에서 진짜 paymentKey 를 얻을 때,
	 * 그리고 IN_DOUBT 로 남은 결제를 나중에 해소할 때. 같은 질문이라 같은 코드다.
	 *
	 * 조회가 실패해도 던지지 않는다. 예전엔 던졌다 — 승인으로도 거절로도 저장할 수 없어서였다.
	 * 이제 IN_DOUBT 라는 제3의 답이 있으므로 그대로 올려보내고 복구 배치가 다시 묻는다.
	 * Kafka 재시도로 같은 호출을 계속 두드리는 것보다, 상태를 남기고 물러나는 쪽이 낫다.
	 */
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
								// READY/IN_PROGRESS = PG 도 아직 진행 중이다. 다음 배치에서 다시 본다.
								default -> PgChargeResult.inDoubt("PG_" + body.status(),
										"PG 가 아직 처리 중입니다. pgStatus=" + body.status());
							};
						}

						PgErrorResponse error = response.bodyTo(PgErrorResponse.class);
						String code = error == null ? "UNKNOWN_ERROR" : error.code();
						if ("NOT_FOUND_PAYMENT".equals(code)) {
							// PG 에 기록이 없다 = 청구가 아예 안 닿았다. 돈이 안 빠진 게 확인됐으므로 재청구가 안전하다.
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
