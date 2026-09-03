package com.urban6.payment.infra.messaging;

import com.urban6.payment.application.RegisterBillingKeyService;
import com.urban6.payment.support.PaymentKafkaIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 커맨드 리스너가 무엇을 견디는가. order 쪽과 같은 명제를 payment 에서도 지킨다 —
 * 메시지 하나가 파티션 하나를 멈추게 하지 않는다.
 *
 * payment 는 여기서 돈이 나가므로 대가가 더 크다. 커맨드 하나가 파티션을 막으면
 * 같은 파티션의 다른 주문들이 결제되지 않은 채 order 쪽에서 PENDING 으로 굳는다.
 */
class PaymentCommandListenerIntegrationTest extends PaymentKafkaIntegrationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);
	private static final String CUSTOMER_ID = "C-1";

	@Autowired
	private RegisterBillingKeyService registerBillingKeyService;

	private static String newOrderNo() {
		return "ORD-20260903-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
	}

	private void registerCard(String cardNumber) {
		registerBillingKeyService.register(CUSTOMER_ID, cardNumber);
	}

	private static String command(String eventType, String orderNo, String extraPayloadFields) {
		return """
				{"eventId":"%s","eventType":"%s","eventVersion":1,
				 "aggregateId":"%s","partitionKey":"%s","occurredAt":"2026-09-03T00:00:00Z",
				 "payload":{"orderNo":"%s","customerId":"%s","amount":10000%s},"headers":{}}
				""".formatted(UUID.randomUUID(), eventType, orderNo, orderNo, orderNo, CUSTOMER_ID,
				extraPayloadFields);
	}

	private static String approveCommand(String orderNo) {
		return command("APPROVE_PAYMENT", orderNo, "");
	}

	private void awaitPaymentStatus(String orderNo, String expected) {
		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(columnOf("status", orderNo)).isEqualTo(expected));
	}

	@Test
	@DisplayName("승인 커맨드가 브로커를 거쳐 도착하면 청구하고 회신을 적재한다")
	void consumesApproveCommandFromBroker() {
		registerCard("1234567812345678");
		String orderNo = newOrderNo();

		publishCommand(orderNo, approveCommand(orderNo));

		awaitPaymentStatus(orderNo, "DONE");
		assertThat(columnOf("payment_key", orderNo)).startsWith("tgen_");
		assertThat(jdbcTemplate.queryForList(
				"select event_type from outbox where aggregate_id = ?", String.class, orderNo))
				.containsExactly("PAYMENT_APPROVED");
	}

	@Test
	@DisplayName("빌링키가 없으면 PG 를 부르지 않고 거절 회신을 낸다")
	void rejectsWhenNoBillingKeyRegistered() {
		String orderNo = newOrderNo();

		// 예외로 던지면 재시도만 반복하다 order 의 주문이 PENDING 에 굳는다.
		publishCommand(orderNo, approveCommand(orderNo));

		awaitPaymentStatus(orderNo, "ABORTED");
		assertThat(columnOf("failure_code", orderNo)).isEqualTo("NO_BILLING_KEY");
	}

	@Test
	@DisplayName("poison pill 은 스킵되고 같은 파티션의 다음 커맨드가 정상 처리된다")
	void poisonPillDoesNotBlockThePartition() {
		registerCard("1234567812345678");
		String orderNo = newOrderNo();

		// 역직렬화 자체가 불가능한 값. ErrorHandlingDeserializer 가 없으면 여기서 파티션이 막힌다.
		publishCommand(orderNo, "{ not json at all");
		publishCommand(orderNo, approveCommand(orderNo));

		// 같은 키라 같은 파티션이고 순서도 보장된다. 청구됐다는 건 앞 메시지를 넘어갔다는 뜻이다.
		awaitPaymentStatus(orderNo, "DONE");
	}

	@Test
	@DisplayName("모르는 커맨드 타입은 무시되고 다음 커맨드가 정상 처리된다")
	void unknownCommandTypeIsIgnored() {
		registerCard("1234567812345678");
		String orderNo = newOrderNo();

		// CANCEL_PAYMENT 는 원격 보상을 안 넣기로 하면서 CommandType 에서 지운 값이다.
		// 옛 프로듀서가 아직 보내고 있어도 payment 는 조용히 지나가야 한다.
		publishCommand(orderNo, command("CANCEL_PAYMENT", orderNo, ""));
		publishCommand(orderNo, approveCommand(orderNo));

		awaitPaymentStatus(orderNo, "DONE");
		// 모르는 커맨드는 회신을 내지 않는다. 회신이 둘이면 무시가 아니라 처리한 것이다.
		assertThat(countForOrder("outbox", orderNo)).isEqualTo(1);
	}

	@Test
	@DisplayName("모르는 필드가 섞여 있어도 읽는다 — tolerant reader")
	void toleratesUnknownFields() {
		registerCard("1234567812345678");
		String orderNo = newOrderNo();

		// order 가 커맨드 payload 에 필드를 늘려도 payment 는 자기가 쓰는 것만 읽는다.
		publishCommand(orderNo, command("APPROVE_PAYMENT", orderNo,
				",\"couponId\":\"CPN-1\",\"deliveryFee\":3000"));

		awaitPaymentStatus(orderNo, "DONE");
	}

	@Test
	@DisplayName("같은 eventId 로 두 번 발행해도 청구는 한 번, 회신도 하나다")
	void duplicateEventIdChargesOnce() {
		registerCard("1234567812345678");
		String orderNo = newOrderNo();
		String json = approveCommand(orderNo);

		publishCommand(orderNo, json);
		publishCommand(orderNo, json);

		awaitPaymentStatus(orderNo, "DONE");
		// 두 번째 메시지가 늦게 도착할 수 있으니 잠깐 더 보고도 하나여야 한다.
		await().during(Duration.ofSeconds(2)).atMost(TIMEOUT).untilAsserted(() -> {
			assertThat(countForOrder("payment", orderNo)).isEqualTo(1);
			// 회신이 둘이면 order 가 같은 승인을 두 번 본다. 멱등 테이블이 두 번째를 막는다.
			assertThat(countForOrder("outbox", orderNo)).isEqualTo(1);
		});
	}
}
