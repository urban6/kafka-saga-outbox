package com.urban6.payment.application;

import com.urban6.payment.infra.client.PgRetryableException;
import com.urban6.payment.support.PaymentIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PG 판정이 <b>DB 와 회신에 어떻게 옮겨지는가</b>를 본다.
 * <p>
 * {@code PgClient} 판정표는 단위 테스트가 덮었다. 여기서 확인하는 건 그 다음이다 —
 * 승인은 무엇을 남기고, 거절은 무엇을 남기고, <b>재시도는 아무것도 남기지 않으며</b>,
 * 모를 때는 행만 남기고 회신은 내지 않는가. 마지막 둘이 이 클래스의 존재 이유다.
 */
class ApprovePaymentServiceIntegrationTest extends PaymentIntegrationTest {

	private static final String CUSTOMER_ID = "C-1";
	private static final BigDecimal AMOUNT = new BigDecimal("10000.0000");

	@Autowired
	private ApprovePaymentService approvePaymentService;

	@Autowired
	private RegisterBillingKeyService registerBillingKeyService;

	private static String newOrderNo() {
		return "ORD-20260903-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
	}

	private void registerCard(String cardNumber) {
		registerBillingKeyService.register(CUSTOMER_ID, cardNumber);
	}

	@Test
	@DisplayName("승인 → DONE + PG 가 준 paymentKey + 승인 회신")
	void approvedPaymentIsRecordedAndReplied() {
		registerCard("1234567812345678");
		String orderNo = newOrderNo();

		approvePaymentService.approve(UUID.randomUUID(), orderNo, CUSTOMER_ID, AMOUNT);

		assertThat(columnOf("status", orderNo)).isEqualTo("DONE");
		// 우리가 만든 값이 아니라 PG 가 응답으로 확인해준 값이어야 한다.
		assertThat(columnOf("payment_key", orderNo)).startsWith("tgen_");
		assertThat(outboxEventType(orderNo)).isEqualTo("PAYMENT_APPROVED");
		assertThat(countOf("consumed_message")).isEqualTo(1);
	}

	@Test
	@DisplayName("카드사 거절 → ABORTED + 거절 회신. order 가 재고를 풀 수 있어야 한다")
	void rejectedPaymentIsRecordedAndReplied() {
		// 끝자리 0000 은 Mock 의 확정 거절 규칙이다. 확률과 달리 시드만으로 재현된다.
		registerCard("9999888877770000");
		String orderNo = newOrderNo();

		approvePaymentService.approve(UUID.randomUUID(), orderNo, CUSTOMER_ID, AMOUNT);

		assertThat(columnOf("status", orderNo)).isEqualTo("ABORTED");
		assertThat(columnOf("failure_code", orderNo)).isEqualTo("REJECT_CARD_COMPANY");
		assertThat(outboxEventType(orderNo)).isEqualTo("PAYMENT_REJECTED");
	}

	@Test
	@DisplayName("등록된 카드가 없으면 PG 를 부르지 않고 거절 회신을 낸다")
	void missingBillingKeyRejectsWithoutCallingPg() {
		String orderNo = newOrderNo();

		// 예외로 던지면 재시도만 반복하다 주문이 PENDING 에 굳는다. 거절 회신이 나가야 재고가 풀린다.
		approvePaymentService.approve(UUID.randomUUID(), orderNo, CUSTOMER_ID, AMOUNT);

		assertThat(columnOf("status", orderNo)).isEqualTo("ABORTED");
		assertThat(columnOf("failure_code", orderNo)).isEqualTo("NO_BILLING_KEY");
		assertThat(outboxEventType(orderNo)).isEqualTo("PAYMENT_REJECTED");
	}

	@Test
	@DisplayName("재시도 대상은 DB 에 아무것도 남기지 않는다 — 흔적이 남으면 재시도가 영영 막힌다")
	void retryableLeavesNoTrace() {
		registerCard("1234567812345678");
		String orderNo = newOrderNo();
		faults.setErrorRate(1.0);

		assertThatThrownBy(() -> approvePaymentService.approve(UUID.randomUUID(), orderNo, CUSTOMER_ID, AMOUNT))
				.isInstanceOf(PgRetryableException.class);

		// 결제 행이 남으면 uk_order_no 가 다음 시도를 막고,
		// 멱등 선점이 남으면 그 커맨드는 영영 재처리되지 않는다. 둘 다 없어야 Kafka 재시도가 흡수한다.
		assertThat(countOf("payment")).isZero();
		assertThat(countOf("consumed_message")).isZero();
		assertThat(countOf("outbox")).isZero();

		// 장애가 걷히면 같은 커맨드가 그대로 통해야 한다.
		faults.setErrorRate(0);
		approvePaymentService.approve(UUID.randomUUID(), orderNo, CUSTOMER_ID, AMOUNT);
		assertThat(columnOf("status", orderNo)).isEqualTo("DONE");
	}

	@Test
	@DisplayName("타임아웃 → IN_PROGRESS 로 남기고 회신하지 않는다 (돈이 빠졌는지 모른다)")
	void timeoutRecordsInDoubtAndStaysSilent() {
		registerCard("1234567812345678");
		String orderNo = newOrderNo();
		// read-timeout 500ms 보다 길게. 클라이언트는 못 받고 PG 는 처리한다 = in-doubt 그 자체.
		faults.setDelayRate(1.0);
		faults.setDelay(Duration.ofSeconds(2));

		approvePaymentService.approve(UUID.randomUUID(), orderNo, CUSTOMER_ID, AMOUNT);

		assertThat(columnOf("status", orderNo)).isEqualTo("IN_PROGRESS");
		assertThat(columnOf("failure_code", orderNo)).isEqualTo("PG_TIMEOUT");
		assertThat(columnOf("payment_key", orderNo)).isNull();

		// 여기가 핵심이다. 승인 회신은 재고를 확정하고 거절 회신은 재고를 푸는데,
		// 어느 쪽도 틀리면 되돌릴 수 없다. 답을 알 때까지 아무 말도 하지 않는다.
		assertThat(countOf("outbox")).isZero();
	}

	@Test
	@DisplayName("같은 eventId 가 두 번 오면 회신도 다시 내지 않는다")
	void duplicateEventIdIsFullyIgnored() {
		registerCard("1234567812345678");
		String orderNo = newOrderNo();
		UUID eventId = UUID.randomUUID();

		approvePaymentService.approve(eventId, orderNo, CUSTOMER_ID, AMOUNT);
		approvePaymentService.approve(eventId, orderNo, CUSTOMER_ID, AMOUNT);

		assertThat(countOf("payment")).isEqualTo(1);
		assertThat(countOf("consumed_message")).isEqualTo(1);
		assertThat(countOf("outbox")).isEqualTo(1);
	}

	@Test
	@DisplayName("eventId 만 다르면 PG 를 다시 부르지 않고 회신만 재발행한다")
	void newEventIdReplaysReplyWithoutChargingAgain() {
		registerCard("1234567812345678");
		String orderNo = newOrderNo();

		approvePaymentService.approve(UUID.randomUUID(), orderNo, CUSTOMER_ID, AMOUNT);
		String key = columnOf("payment_key", orderNo);

		// 앞선 회신이 유실됐을 수 있다. 안 보내면 order 가 영원히 대기한다.
		approvePaymentService.approve(UUID.randomUUID(), orderNo, CUSTOMER_ID, AMOUNT);

		assertThat(countOf("payment")).isEqualTo(1);
		// PG 를 다시 불렀다면 ALREADY_PROCESSED_PAYMENT 경로를 타 키가 바뀌었을 수도 있다.
		assertThat(columnOf("payment_key", orderNo)).isEqualTo(key);
		assertThat(countOf("outbox")).isEqualTo(2);
		assertThat(countOf("consumed_message")).isEqualTo(2);
	}
}
