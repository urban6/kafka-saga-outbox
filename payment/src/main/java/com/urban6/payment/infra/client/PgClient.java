package com.urban6.payment.infra.client;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Mock PG 호출.
 * <p>
 * 이 클래스가 하는 일은 <b>Toss 에러 코드를 우리 결론으로 번역</b>하는 것 하나다.
 * 결제 도메인은 {@code ALREADY_PROCESSED_PAYMENT} 같은 문자열을 알 필요가 없다.
 * <p>
 * 멱등키는 {@code order_no} 다. 같은 주문을 두 번 청구해도 PG 가 한 번만 처리한다.
 * <b>트랜잭션 밖에서 호출한다</b> — 외부 I/O 가 트랜잭션 안에 들어오면 PG 가 느려질 때
 * DB 커넥션이 그만큼 잡혀 있는다.
 */
@Component
@RequiredArgsConstructor
public class PgClient {

	private static final Logger log = LoggerFactory.getLogger(PgClient.class);
	private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

	/** Toss 빌링 청구 본문. {@code orderName} 은 필수인데 payment 는 상품명을 모르므로 주문번호를 넣는다. */
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
	 * Toss {@code POST /v1/billing/authorizations/card}. 카드번호는 이 호출로 우리 손을 떠난다.
	 * <p>
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
	 * Toss {@code POST /v1/billing/{billingKey}}. 여기서 돈이 빠진다.
	 * <p>
	 * 빌링키는 URL 에, 고객 식별자는 본문에 간다. PG 는 둘이 같은 고객의 것인지 대조한다 —
	 * 그래서 다른 고객의 키로는 청구가 안 된다.
	 */
	public PgChargeResult charge(String orderNo, String billingKey, String customerId, BigDecimal amount) {
		return restClient.post()
				.uri("/v1/billing/{billingKey}", billingKey)
				.header(IDEMPOTENCY_KEY, orderNo)
				.body(new ChargeRequest(customerId, orderNo, orderNo, amount))
				.exchange((request, response) -> map(orderNo, response));
	}

	/**
	 * {@code exchange} 를 쓰는 이유: 기본 에러 핸들링은 4xx/5xx 에 예외를 던져버려서
	 * 에러 본문의 {@code code} 를 읽을 수 없다. 우리에겐 그 코드가 판단 근거다.
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
			case "ALREADY_PROCESSED_PAYMENT" -> lookup(orderNo);

			case "REJECT_CARD_COMPANY" -> PgChargeResult.rejected(code, message);

			// DB 엔 키가 있는데 PG 가 모른다 — 카드 폐기·만료 상황이다. 돈은 안 빠졌고 재시도해도 같다.
			// (Mock 은 인메모리라 payment 앱을 재시작하면 이 경로가 실제로 나온다. 재등록으로 복구)
			case "NOT_FOUND_BILLING_KEY" -> PgChargeResult.rejected(code, message);

			default -> {
				// 아직 재시도 판정을 만들지 않았다. 승인되지 않았다는 것만 확실하므로 거절로 본다.
				log.warn("unmapped pg error code. orderNo={} code={}", orderNo, code);
				yield PgChargeResult.rejected(code, message);
			}
		};
	}

	/**
	 * 조회 API 로 PG 가 실제로 들고 있는 결제를 확인한다.
	 * <p>
	 * 지금은 "이미 처리된 결제" 응답에서 진짜 {@code paymentKey} 를 얻으려고 쓴다.
	 * 나중에 타임아웃·5xx 로 결과를 모를 때(in-doubt) 무작정 재청구하지 않고 여기부터 보게 된다 —
	 * 같은 경로를 재사용하면 된다.
	 * <p>
	 * 조회마저 실패하면 <b>던진다.</b> 돈은 빠졌는데 키를 모르는 상태라 승인으로 저장할 수도
	 * (틀린 키가 남는다) 거절할 수도(보상이 돈다) 없다. 예외로 나가면 Kafka 가 재시도한다.
	 */
	private PgChargeResult lookup(String orderNo) {
		return restClient.get()
				.uri("/v1/payments/orders/{orderId}", orderNo)
				.exchange((request, response) -> {
					HttpStatusCode status = response.getStatusCode();
					if (!status.is2xxSuccessful()) {
						log.warn("pg lookup failed after ALREADY_PROCESSED_PAYMENT. orderNo={} httpStatus={}",
								orderNo, status.value());
						throw new PgCallException(status.value(), "LOOKUP_FAILED",
								"이미 처리된 결제의 조회에 실패했습니다. orderNo=" + orderNo);
					}

					PgPaymentResponse body = response.bodyTo(PgPaymentResponse.class);
					log.info("pg lookup. orderNo={} pgStatus={} paymentKey={}",
							orderNo, body.status(), body.paymentKey());

					return "DONE".equals(body.status())
							? PgChargeResult.approved(body.paymentKey())
							: PgChargeResult.rejected("ALREADY_PROCESSED_PAYMENT",
									"이미 종료된 결제 입니다. pgStatus=" + body.status());
				});
	}
}
