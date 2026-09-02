package com.urban6.payment.infra.client;

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
 * 멱등키는 {@code order_no} 다. 같은 주문을 두 번 승인 요청해도 PG 가 한 번만 처리한다.
 * <b>트랜잭션 밖에서 호출한다</b> — 외부 I/O 가 트랜잭션 안에 들어오면 PG 가 느려질 때
 * DB 커넥션이 그만큼 잡혀 있는다.
 */
@Component
public class PgClient {

	private static final Logger log = LoggerFactory.getLogger(PgClient.class);
	private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

	private record ConfirmRequest(String paymentKey, String orderId, BigDecimal amount) {
	}

	/** 응답 본문. 우리가 쓰는 필드만 선언한다 — PG 가 필드를 늘려도 안 깨진다. */
	private record PgPaymentResponse(String paymentKey, String orderId, String status) {
	}

	private record PgErrorResponse(String code, String message) {
	}

	private final RestClient restClient;

	public PgClient(RestClient pgRestClient) {
		this.restClient = pgRestClient;
	}

	public PgConfirmResult confirm(String orderNo, String paymentKey, BigDecimal amount) {
		return restClient.post()
				.uri("/v1/payments/confirm")
				.header(IDEMPOTENCY_KEY, orderNo)
				.body(new ConfirmRequest(paymentKey, orderNo, amount))
				.exchange((request, response) -> map(orderNo, paymentKey, response));
	}

	/**
	 * {@code exchange} 를 쓰는 이유: 기본 에러 핸들링은 4xx/5xx 에 예외를 던져버려서
	 * 에러 본문의 {@code code} 를 읽을 수 없다. 우리에겐 그 코드가 판단 근거다.
	 */
	private PgConfirmResult map(String orderNo, String sentPaymentKey,
			RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) throws IOException {

		HttpStatusCode status = response.getStatusCode();
		if (status.is2xxSuccessful()) {
			PgPaymentResponse body = response.bodyTo(PgPaymentResponse.class);
			return PgConfirmResult.approved(body.paymentKey());
		}

		PgErrorResponse error = response.bodyTo(PgErrorResponse.class);
		String code = error == null ? "UNKNOWN_ERROR" : error.code();
		String message = error == null ? status.toString() : error.message();
		log.info("pg confirm error. orderNo={} httpStatus={} code={}", orderNo, status.value(), code);

		return switch (code) {
			// 실패가 아니라 성공이다. 여기서 잘못 판단하면 이미 받은 돈에 대해
			// 재고 해제와 주문 취소가 돌아 최악이 된다.
			//
			// 다만 이 응답에는 paymentKey 가 없다. 우리가 보낸 값을 그대로 쓰면 안 된다 —
			// 실제 결제를 성사시킨 건 이전 시도이고 PG 는 그때의 키를 들고 있다.
			// 틀린 키를 저장하면 나중에 그 키로 취소할 때 없는 결제를 취소하게 된다.
			case "ALREADY_PROCESSED_PAYMENT" -> lookup(orderNo, sentPaymentKey);

			case "REJECT_CARD_COMPANY" -> PgConfirmResult.rejected(code, message);

			default -> {
				// 아직 재시도 판정을 만들지 않았다. 승인되지 않았다는 것만 확실하므로 거절로 본다.
				log.warn("unmapped pg error code. orderNo={} code={}", orderNo, code);
				yield PgConfirmResult.rejected(code, message);
			}
		};
	}

	/**
	 * 조회 API 로 PG 가 실제로 들고 있는 결제를 확인한다.
	 * <p>
	 * 지금은 "이미 처리된 결제" 응답에서 진짜 {@code paymentKey} 를 얻으려고 쓴다.
	 * 나중에 타임아웃·5xx 로 결과를 모를 때(in-doubt) 무작정 재승인하지 않고 여기부터 보게 된다 —
	 * 같은 경로를 재사용하면 된다.
	 *
	 * @param fallbackPaymentKey 조회가 실패했을 때 쓸 값. PG 가 "이미 처리됨"이라고 말한 이상
	 *                           승인으로 보는 게 맞고, 키만 정확하지 않을 뿐이다
	 */
	private PgConfirmResult lookup(String orderNo, String fallbackPaymentKey) {
		return restClient.get()
				.uri("/v1/payments/orders/{orderId}", orderNo)
				.exchange((request, response) -> {
					HttpStatusCode status = response.getStatusCode();
					if (!status.is2xxSuccessful()) {
						log.warn("pg lookup failed. orderNo={} httpStatus={}", orderNo, status.value());
						return PgConfirmResult.approved(fallbackPaymentKey);
					}

					PgPaymentResponse body = response.bodyTo(PgPaymentResponse.class);
					log.info("pg lookup. orderNo={} pgStatus={} paymentKey={}",
							orderNo, body.status(), body.paymentKey());

					return "DONE".equals(body.status())
							? PgConfirmResult.approved(body.paymentKey())
							: PgConfirmResult.rejected("ALREADY_PROCESSED_PAYMENT",
									"이미 종료된 결제 입니다. pgStatus=" + body.status());
				});
	}
}
