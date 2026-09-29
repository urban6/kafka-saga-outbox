package com.urban6.payment.infra.messaging;

import com.urban6.payment.application.RegisterBillingKeyService;
import com.urban6.payment.support.PaymentKafkaIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.support.KafkaHeaders;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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

		publishCommand(orderNo, approveCommand(orderNo));

		awaitPaymentStatus(orderNo, "ABORTED");
		assertThat(columnOf("failure_code", orderNo)).isEqualTo("NO_BILLING_KEY");
	}

	@Test
	@DisplayName("poison pill 은 스킵되고 같은 파티션의 다음 커맨드가 정상 처리된다")
	void poisonPillDoesNotBlockThePartition() {
		registerCard("1234567812345678");
		String orderNo = newOrderNo();

		publishCommand(orderNo, "{ not json at all");
		publishCommand(orderNo, approveCommand(orderNo));

		// 같은 키 = 같은 파티션. 청구됐다면 앞 메시지를 넘어간 것이다.
		awaitPaymentStatus(orderNo, "DONE");
	}

	@Test
	@DisplayName("poison pill 은 원본 바이트 그대로 DLT 에 남는다 — 스킵은 폐기가 아니다")
	void poisonPillIsPublishedToDlt() {
		String orderNo = newOrderNo();
		String broken = "{ not json at all";

		publishCommand(orderNo, broken);

		ConsumerRecord<String, String> dead = awaitDltRecord(orderNo);

		assertThat(dead.value()).isEqualTo(broken);
		assertThat(headerOf(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(Topics.PAYMENT_COMMANDS);
		assertThat(headerOf(dead, KafkaHeaders.DLT_EXCEPTION_FQCN)).contains("DeserializationException");
	}

	@Test
	@DisplayName("PG 장애가 재시도 예산보다 길면 커맨드가 DLT 에 남는다 — 재생하면 그만이다")
	void pgOutageBeyondRetryBudgetIsPublishedToDlt() {
		registerCard("1234567812345678");
		String orderNo = newOrderNo();

		faults.setErrorRate(1.0);

		publishCommand(orderNo, approveCommand(orderNo));

		ConsumerRecord<String, String> dead = awaitDltRecord(orderNo);

		// RETRYABLE 은 DB 에 흔적이 없어 DLT 가 유일한 흔적이다.
		assertThat(countForOrder("payment", orderNo)).isZero();
		assertThat(countForOrder("outbox", orderNo)).isZero();

		assertThat(dead.value()).contains("APPROVE_PAYMENT").contains(orderNo);
		assertThat(headerOf(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(Topics.PAYMENT_COMMANDS);
		// 리스너 예외는 감싸여 오므로 진짜 원인은 cause 헤더에 있다.
		assertThat(headerOf(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)).contains("PgRetryableException");
	}

	@Test
	@DisplayName("모르는 커맨드 타입은 무시되고 다음 커맨드가 정상 처리된다")
	void unknownCommandTypeIsIgnored() {
		registerCard("1234567812345678");
		String orderNo = newOrderNo();

		// CommandType 에서 지운 값. 옛 프로듀서가 보내도 무시돼야 한다.
		publishCommand(orderNo, command("CANCEL_PAYMENT", orderNo, ""));
		publishCommand(orderNo, approveCommand(orderNo));

		awaitPaymentStatus(orderNo, "DONE");
		assertThat(countForOrder("outbox", orderNo)).isEqualTo(1);
	}

	@Test
	@DisplayName("모르는 필드가 섞여 있어도 읽는다 — tolerant reader")
	void toleratesUnknownFields() {
		registerCard("1234567812345678");
		String orderNo = newOrderNo();

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
		// 두 번째가 늦게 올 수 있어 잠깐 더 지켜본다.
		await().during(Duration.ofSeconds(2)).atMost(TIMEOUT).untilAsserted(() -> {
			assertThat(countForOrder("payment", orderNo)).isEqualTo(1);
			assertThat(countForOrder("outbox", orderNo)).isEqualTo(1);
		});
	}
}
