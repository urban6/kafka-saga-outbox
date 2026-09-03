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
 * "돈이 빠졌는지 모르는" 결제를 조회로 해소하는 경로.
 * <p>
 * <b>준비를 타임아웃 주입으로 하지 않는다.</b> 그러면 Mock PG 의 지연이 끝나기를 기다려야 해서
 * 테스트가 시간에 의존한다. 대신 PG 와 우리 DB 를 <b>따로</b> 원하는 상태로 만든다 —
 * PG 에는 청구를 직접 넣어 {@code DONE} 을 만들고, 우리 쪽엔 {@code IN_PROGRESS} 행을 심는다.
 * in-doubt 는 결국 "두 상태가 어긋난 것" 이므로 이렇게 만드는 편이 정확하고 빠르다.
 * (타임아웃이 실제로 {@code IN_PROGRESS} 를 만드는지는 {@code ApprovePaymentServiceIntegrationTest} 가 본다)
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

	/** PG 에만 결제를 성사시킨다. 우리 DB 는 건드리지 않는다 — 응답이 유실된 상황과 같은 상태다. */
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

	/**
	 * 결과를 모르는 결제 행. {@code updated_at} 을 과거로 박아 grace(기본 30초)를 통과시킨다.
	 * 시각을 MySQL 이 계산하게 두는 이유는 애플리케이션과 DB 의 시간대 해석을 섞지 않기 위해서다.
	 */
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
		// 우리가 못 받았던 그 키를 조회로 되찾아온다. 이게 없으면 나중에 취소할 결제를 특정할 수 없다.
		assertThat(columnOf("payment_key", orderNo)).startsWith("tgen_");
		// DONE 인 행에 failure 가 남아 있으면 컬럼 이름이 거짓말을 한다.
		assertThat(columnOf("failure_code", orderNo)).isNull();
		assertThat(columnOf("failure_reason", orderNo)).isNull();

		// 접수 때 미뤄뒀던 회신이 이제 나간다. 안 나가면 order 는 영원히 PENDING 이다.
		assertThat(outboxEventType(orderNo)).isEqualTo("PAYMENT_APPROVED");
	}

	@Test
	@DisplayName("PG 에 기록이 없으면 미체결로 확정한다 — 돈이 안 빠진 게 확인됐다")
	void resolvesToRejectionWhenPgHasNoRecord() {
		String orderNo = newOrderNo();
		// PG 에는 아무것도 넣지 않는다. 청구가 아예 안 닿은 상황이다.
		insertInDoubtRow(orderNo, 10);

		inDoubtRecoveryService.recover();

		assertThat(columnOf("status", orderNo)).isEqualTo("ABORTED");
		assertThat(columnOf("failure_code", orderNo)).isEqualTo("PG_NO_RECORD");
		// 거절 회신이 나가야 order 가 재고를 푼다.
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

		// 이미 DONE 이라 findInDoubtBefore 에 안 걸리고, 걸렸더라도 settle 이 0건으로 물러난다.
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

		// 건별 트랜잭션이라 한 건이 실패해도 다른 건의 확정이 함께 날아가지 않는다.
		assertThat(columnOf("status", settled)).isEqualTo("DONE");
		assertThat(columnOf("status", missing)).isEqualTo("ABORTED");
		assertThat(countOf("outbox")).isEqualTo(2);
	}
}
