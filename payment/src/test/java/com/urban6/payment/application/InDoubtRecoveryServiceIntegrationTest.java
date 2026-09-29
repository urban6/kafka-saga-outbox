package com.urban6.payment.application;

import com.urban6.payment.domain.BillingKey;
import com.urban6.payment.support.PaymentIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * in-doubt 를 타임아웃 주입이 아니라 상태를 직접 심어 만든다(PG 엔 청구, DB 엔 IN_PROGRESS).
 * in-doubt 는 두 상태가 어긋난 것이라 이편이 정확하고 시간에 의존하지 않는다.
 */
class InDoubtRecoveryServiceIntegrationTest extends PaymentIntegrationTest {

	private static final String CUSTOMER_ID = "C-1";
	private static final BigDecimal AMOUNT = new BigDecimal("10000.0000");

	@Autowired
	private InDoubtRecoveryService inDoubtRecoveryService;

	@Autowired
	private RegisterBillingKeyService registerBillingKeyService;

	private static String newOrderNo() {
		return "ORD-20260903-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
	}

	/** 응답이 유실된 상황: PG 에만 결제를 성사시킨다. */
	private void chargeOnPgOnly(String orderNo) {
		BillingKey billingKey = registerBillingKeyService.register(CUSTOMER_ID, "1234567812345678");
		pg.post()
				.uri("/v1/billing/{billingKey}", billingKey.getBillingKey())
				.header("Idempotency-Key", orderNo)
				.body(Map.of("customerKey", CUSTOMER_ID, "orderId", orderNo,
						"orderName", orderNo, "amount", AMOUNT))
				.retrieve()
				.toBodilessEntity();
	}

	/** 시각은 MySQL 이 계산한다 — 앱과 DB 의 시간대 해석을 섞지 않는다. */
	private void insertInDoubtRow(String orderNo, int minutesAgo) {
		jdbcTemplate.update("""
				insert into payment
				    (payment_id, order_no, amount, status, failure_code, failure_reason, created_at, updated_at)
				values (?, ?, ?, 'IN_PROGRESS', 'PG_TIMEOUT', 'PG 응답을 받지 못했습니다',
				        now(6) - interval ? minute, now(6) - interval ? minute)
				""", "PAY-" + UUID.randomUUID(), orderNo, AMOUNT, minutesAgo, minutesAgo);
	}

	@Test
	@DisplayName("PG 는 DONE 인데 우리는 모르던 결제 → 조회로 승인 확정 + 미뤄둔 회신을 낸다")
	void resolvesInDoubtToApprovalUsingLookup() {
		String orderNo = newOrderNo();
		chargeOnPgOnly(orderNo);
		insertInDoubtRow(orderNo, 10);

		inDoubtRecoveryService.recover();

		assertThat(columnOf("status", orderNo)).isEqualTo("DONE");
		assertThat(columnOf("payment_key", orderNo)).startsWith("tgen_");
		assertThat(columnOf("failure_code", orderNo)).isNull();
		assertThat(columnOf("failure_reason", orderNo)).isNull();

		assertThat(outboxEventType(orderNo)).isEqualTo("PAYMENT_APPROVED");
	}

	@Test
	@DisplayName("PG 에 기록이 없으면 미체결로 확정한다 — 돈이 안 빠진 게 확인됐다")
	void resolvesToRejectionWhenPgHasNoRecord() {
		String orderNo = newOrderNo();
		insertInDoubtRow(orderNo, 10);

		inDoubtRecoveryService.recover();

		assertThat(columnOf("status", orderNo)).isEqualTo("ABORTED");
		assertThat(columnOf("failure_code", orderNo)).isEqualTo("PG_NO_RECORD");
		assertThat(outboxEventType(orderNo)).isEqualTo("PAYMENT_REJECTED");
	}

	@Test
	@DisplayName("갓 만들어진 미결은 건드리지 않는다 — PG 응답이 조금 늦었을 뿐일 수 있다")
	void skipsRowsInsideGracePeriod() {
		String orderNo = newOrderNo();
		chargeOnPgOnly(orderNo);
		insertInDoubtRow(orderNo, 0);

		inDoubtRecoveryService.recover();

		assertThat(columnOf("status", orderNo)).isEqualTo("IN_PROGRESS");
		assertThat(countOf("outbox")).isZero();
	}

	@Test
	@DisplayName("두 번 돌려도 회신이 두 번 나가지 않는다 — 조건부 UPDATE 가 막는다")
	void secondRunIsANoOp() {
		String orderNo = newOrderNo();
		chargeOnPgOnly(orderNo);
		insertInDoubtRow(orderNo, 10);

		inDoubtRecoveryService.recover();
		inDoubtRecoveryService.recover();

		assertThat(countOf("outbox")).isEqualTo(1);
		assertThat(columnOf("status", orderNo)).isEqualTo("DONE");
	}

	@Test
	@DisplayName("여러 건이 섞여 있어도 각자 제 결론으로 확정된다")
	void resolvesMixedBatchIndependently() {
		String settled = newOrderNo();
		String missing = newOrderNo();
		chargeOnPgOnly(settled);
		insertInDoubtRow(settled, 10);
		insertInDoubtRow(missing, 10);

		inDoubtRecoveryService.recover();

		assertThat(columnOf("status", settled)).isEqualTo("DONE");
		assertThat(columnOf("status", missing)).isEqualTo("ABORTED");
		assertThat(countOf("outbox")).isEqualTo(2);
	}
}
