package com.urban6.payment.infra.client;

import com.urban6.payment.infra.client.PgChargeResult.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * PgClient 판정표. 표가 곧 테스트 케이스다.
 *
 * 여기서 지키는 명제는 하나다 — "모른다" 를 "거절" 로 접지 않는다.
 * 예전 코드는 default 분기가 모르는 코드를 전부 거절로 떨어뜨렸고,
 * 그래서 타임아웃 뒤 승인된 결제에 재고 해제와 주문 취소가 도는 경로가 열려 있었다.
 * 이 클래스가 그 회귀를 막는다.
 */
class PgClientTest {

	private static final String ORDER_NO = "ORD-20260903-TEST0001";
	private static final String BILLING_KEY = "billing_test";
	private static final String CUSTOMER_ID = "C-1";
	private static final BigDecimal AMOUNT = new BigDecimal("10000");

	/** requestTo 는 절대 URL 로 비교한다. baseUrl 을 붙여둔다. */
	private static final String BASE_URL = "http://pg.test";
	private static final String CHARGE_URI = BASE_URL + "/v1/billing/" + BILLING_KEY;
	private static final String LOOKUP_URI = BASE_URL + "/v1/payments/orders/" + ORDER_NO;

	private MockRestServiceServer server;
	private PgClient pgClient;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		pgClient = new PgClient(builder.build());
	}

	private static String error(String code) {
		return "{\"code\":\"" + code + "\",\"message\":\"테스트\"}";
	}

	private void respondToCharge(HttpStatus status, String body) {
		server.expect(requestTo(CHARGE_URI))
				// 멱등키는 order_no 다. 이게 빠지면 PG 가 같은 주문을 두 번 처리한다.
				.andExpect(header("Idempotency-Key", ORDER_NO))
				.andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body));
	}

	private PgChargeResult charge() {
		PgChargeResult result = pgClient.charge(ORDER_NO, BILLING_KEY, CUSTOMER_ID, AMOUNT);
		server.verify();
		return result;
	}

	// ── 확정된 결론 ────────────────────────────────────────────

	@Test
	@DisplayName("2xx → 승인. paymentKey 는 PG 가 준 값을 그대로 쓴다")
	void success() {
		server.expect(requestTo(CHARGE_URI)).andRespond(withSuccess(
				"{\"paymentKey\":\"tgen_abc\",\"orderId\":\"" + ORDER_NO + "\",\"status\":\"DONE\"}",
				MediaType.APPLICATION_JSON));

		PgChargeResult result = charge();

		assertThat(result.outcome()).isEqualTo(Outcome.APPROVED);
		assertThat(result.paymentKey()).isEqualTo("tgen_abc");
	}

	@Test
	@DisplayName("REJECT_CARD_COMPANY → 거절. 돈이 안 빠졌고 재시도해도 같다")
	void cardCompanyRejection() {
		respondToCharge(HttpStatus.BAD_REQUEST, error("REJECT_CARD_COMPANY"));

		PgChargeResult result = charge();

		assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
		assertThat(result.failureCode()).isEqualTo("REJECT_CARD_COMPANY");
	}

	@Test
	@DisplayName("NOT_FOUND_BILLING_KEY → 거절. 카드 폐기·만료라 재시도가 의미 없다")
	void unknownBillingKey() {
		respondToCharge(HttpStatus.NOT_FOUND, error("NOT_FOUND_BILLING_KEY"));

		assertThat(charge().outcome()).isEqualTo(Outcome.REJECTED);
	}

	// ── 재시도 ────────────────────────────────────────────────

	@Test
	@DisplayName("IDEMPOTENT_REQUEST_PROCESSING(409) → 재시도. 앞선 요청이 아직 처리 중이다")
	void inFlightDuplicateIsRetryable() {
		respondToCharge(HttpStatus.CONFLICT, error("IDEMPOTENT_REQUEST_PROCESSING"));

		assertThat(charge().outcome()).isEqualTo(Outcome.RETRYABLE);
	}

	@Test
	@DisplayName("FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING(500) → 재시도. PG 가 처리 못 했다고 말했다")
	void pgInternalErrorIsRetryable() {
		respondToCharge(HttpStatus.INTERNAL_SERVER_ERROR, error("FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING"));

		assertThat(charge().outcome()).isEqualTo(Outcome.RETRYABLE);
	}

	// ── 이미 처리된 결제 → 조회로 진짜 키를 가져온다 ─────────────

	@Test
	@DisplayName("ALREADY_PROCESSED_PAYMENT → 실패가 아니라 성공. 조회로 진짜 paymentKey 를 얻는다")
	void alreadyProcessedResolvesToApprovalViaLookup() {
		// 이 응답에는 paymentKey 가 없다. 빌링은 우리가 보낸 키도 없어서 조회 말고는 알 방법이 없다.
		respondToCharge(HttpStatus.BAD_REQUEST, error("ALREADY_PROCESSED_PAYMENT"));
		server.expect(requestTo(LOOKUP_URI)).andRespond(withSuccess(
				"{\"paymentKey\":\"tgen_real\",\"orderId\":\"" + ORDER_NO + "\",\"status\":\"DONE\"}",
				MediaType.APPLICATION_JSON));

		PgChargeResult result = charge();

		assertThat(result.outcome()).isEqualTo(Outcome.APPROVED);
		assertThat(result.paymentKey()).isEqualTo("tgen_real");
	}

	@Test
	@DisplayName("조회가 실패하면 던지지 않고 IN_DOUBT — 돈은 빠졌는데 키를 모르는 상태다")
	void lookupFailureBecomesInDoubt() {
		respondToCharge(HttpStatus.BAD_REQUEST, error("ALREADY_PROCESSED_PAYMENT"));
		server.expect(requestTo(LOOKUP_URI))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
						.contentType(MediaType.APPLICATION_JSON).body(error("UNKNOWN_ERROR")));

		assertThat(charge().outcome()).isEqualTo(Outcome.IN_DOUBT);
	}

	// ── 여기가 핵심: 모르는 것은 거절이 아니다 ────────────────────

	@Test
	@DisplayName("모르는 코드 → IN_DOUBT. 거절로 접으면 승인된 결제에 보상이 돈다")
	void unknownCodeIsInDoubtNotRejected() {
		respondToCharge(HttpStatus.BAD_REQUEST, error("SOME_NEW_TOSS_CODE"));

		PgChargeResult result = charge();

		assertThat(result.outcome())
				.as("모르는 코드는 돈이 빠졌는지도 모른다. 재청구도 보상도 아닌 조회가 답이다")
				.isEqualTo(Outcome.IN_DOUBT);
		assertThat(result.isSettled()).isFalse();
	}

	// ── reconcile 판정표 ──────────────────────────────────────

	@Test
	@DisplayName("reconcile: pgStatus=DONE → 승인")
	void reconcileDone() {
		server.expect(requestTo(LOOKUP_URI)).andRespond(withSuccess(
				"{\"paymentKey\":\"tgen_x\",\"orderId\":\"" + ORDER_NO + "\",\"status\":\"DONE\"}",
				MediaType.APPLICATION_JSON));

		PgChargeResult result = pgClient.reconcile(ORDER_NO);
		server.verify();

		assertThat(result.outcome()).isEqualTo(Outcome.APPROVED);
		assertThat(result.paymentKey()).isEqualTo("tgen_x");
	}

	@Test
	@DisplayName("reconcile: pgStatus=ABORTED → 거절")
	void reconcileAborted() {
		server.expect(requestTo(LOOKUP_URI)).andRespond(withSuccess(
				"{\"paymentKey\":\"tgen_x\",\"orderId\":\"" + ORDER_NO + "\",\"status\":\"ABORTED\"}",
				MediaType.APPLICATION_JSON));

		assertThat(pgClient.reconcile(ORDER_NO).outcome()).isEqualTo(Outcome.REJECTED);
	}

	@Test
	@DisplayName("reconcile: NOT_FOUND_PAYMENT → 재시도. 청구가 안 닿았으니 돈이 안 빠진 게 확인됐다")
	void reconcileNoRecordIsRetryable() {
		server.expect(requestTo(LOOKUP_URI))
				.andRespond(withStatus(HttpStatus.NOT_FOUND)
						.contentType(MediaType.APPLICATION_JSON).body(error("NOT_FOUND_PAYMENT")));

		assertThat(pgClient.reconcile(ORDER_NO).outcome()).isEqualTo(Outcome.RETRYABLE);
	}

	@Test
	@DisplayName("reconcile: PG 도 아직 처리 중이면 여전히 IN_DOUBT")
	void reconcileStillProcessing() {
		server.expect(requestTo(LOOKUP_URI)).andRespond(withSuccess(
				"{\"paymentKey\":null,\"orderId\":\"" + ORDER_NO + "\",\"status\":\"IN_PROGRESS\"}",
				MediaType.APPLICATION_JSON));

		assertThat(pgClient.reconcile(ORDER_NO).outcome()).isEqualTo(Outcome.IN_DOUBT);
	}
}
