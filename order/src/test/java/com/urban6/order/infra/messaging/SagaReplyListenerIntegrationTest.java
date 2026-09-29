package com.urban6.order.infra.messaging;

import com.urban6.order.application.PlaceOrderCommand;
import com.urban6.order.application.PlaceOrderService;
import com.urban6.order.support.OrderKafkaIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.support.KafkaHeaders;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SagaReplyListenerIntegrationTest extends OrderKafkaIntegrationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	@Autowired
	private PlaceOrderService placeOrderService;

	private String placedOrder(int quantity) {
		return placeOrderService.place(UUID.randomUUID().toString(),
				new PlaceOrderCommand("C-1", List.of(new PlaceOrderCommand.Item("P-1001", quantity))))
				.orderNo();
	}

	private static String envelope(String eventId, String eventType, String orderNo, String extraPayloadFields) {
		return """
				{"eventId":"%s","eventType":"%s","eventVersion":1,
				 "aggregateId":"%s","partitionKey":"%s","occurredAt":"2026-09-03T00:00:00Z",
				 "payload":{"orderNo":"%s"%s},"headers":{}}
				""".formatted(eventId, eventType, orderNo, orderNo, orderNo, extraPayloadFields);
	}

	private static String approvalOf(String orderNo) {
		return envelope(UUID.randomUUID().toString(), "PAYMENT_APPROVED", orderNo, ",\"paymentKey\":\"tgen_x\"");
	}

	private void publishReply(String orderNo, String json) {
		publishRaw(Topics.ORDER_SAGA_REPLIES, orderNo, json);
	}

	private void awaitOrder(String orderNo, String expected) {
		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(statusOfOrder(orderNo)).isEqualTo(expected));
	}

	@Test
	@DisplayName("승인 회신이 브로커를 거쳐 도착하면 주문이 완료된다")
	void consumesApprovalFromBroker() {
		String orderNo = placedOrder(2);

		publishReply(orderNo, approvalOf(orderNo));

		awaitOrder(orderNo, "COMPLETED");
		assertThat(reservedOf("P-1001")).isZero();
	}

	@Test
	@DisplayName("거절 회신이 도착하면 재고가 풀리고 주문이 취소된다")
	void consumesRejectionFromBroker() {
		String orderNo = placedOrder(3);
		String json = envelope(UUID.randomUUID().toString(), "PAYMENT_REJECTED", orderNo,
				",\"failureCode\":\"REJECT_CARD_COMPANY\",\"failureReason\":\"카드사 거절\"");

		publishReply(orderNo, json);

		awaitOrder(orderNo, "CANCELED");
		assertThat(reservedOf("P-1001")).isZero();
	}

	@Test
	@DisplayName("poison pill 은 스킵되고 같은 파티션의 다음 메시지가 정상 처리된다")
	void poisonPillDoesNotBlockThePartition() {
		String orderNo = placedOrder(1);

		// 같은 키라 같은 파티션이다. 완료됐다면 앞 메시지를 넘어간 것이다.
		publishReply(orderNo, "{ this is not json");
		publishReply(orderNo, approvalOf(orderNo));

		awaitOrder(orderNo, "COMPLETED");
	}

	@Test
	@DisplayName("poison pill 은 원본 바이트 그대로 DLT 에 남는다 — 스킵은 폐기가 아니다")
	void poisonPillIsPublishedToDlt() {
		String orderNo = placedOrder(1);
		String broken = "{ this is not json";

		publishReply(orderNo, broken);

		ConsumerRecord<String, String> dead = awaitDltRecord(orderNo);

		assertThat(dead.value()).isEqualTo(broken);
		assertThat(headerOf(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(Topics.ORDER_SAGA_REPLIES);
		assertThat(headerOf(dead, KafkaHeaders.DLT_EXCEPTION_FQCN)).contains("DeserializationException");
	}

	@Test
	@DisplayName("재시도를 소진한 회신은 봉투째 DLT 에 남는다 — 돈이 빠진 승인을 잃지 않는다")
	void exhaustedRetriesArePublishedToDlt() {
		String orderNo = placedOrder(1);

		// 사가와 주문을 어긋나게 만들어 주문 전이가 매번 0건 → 던진다.
		jdbcTemplate.update("update orders set status = 'CANCELED' where order_no = ?", orderNo);

		publishReply(orderNo, approvalOf(orderNo));

		ConsumerRecord<String, String> dead = awaitDltRecord(orderNo);

		assertThat(dead.value()).contains("PAYMENT_APPROVED").contains(orderNo).contains("tgen_x");
		assertThat(headerOf(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(Topics.ORDER_SAGA_REPLIES);

		// 리스너 예외는 감싸여 오므로 진짜 원인은 cause 헤더에 있다.
		assertThat(headerOf(dead, KafkaHeaders.DLT_EXCEPTION_FQCN)).contains("ListenerExecutionFailedException");
		assertThat(headerOf(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)).contains("IllegalStateException");
	}

	@Test
	@DisplayName("모르는 eventType 은 무시되고 다음 메시지가 정상 처리된다")
	void unknownEventTypeIsIgnored() {
		String orderNo = placedOrder(1);

		publishReply(orderNo, envelope(UUID.randomUUID().toString(), "PAYMENT_PARTIALLY_REFUNDED", orderNo, ""));
		publishReply(orderNo, approvalOf(orderNo));

		awaitOrder(orderNo, "COMPLETED");
	}

	@Test
	@DisplayName("모르는 필드가 섞여 있어도 읽는다 — tolerant reader")
	void toleratesUnknownFields() {
		String orderNo = placedOrder(1);

		String json = """
				{"eventId":"%s","eventType":"PAYMENT_APPROVED","eventVersion":1,
				 "aggregateId":"%s","partitionKey":"%s","occurredAt":"2026-09-03T00:00:00Z",
				 "traceId":"abc-123","producedBy":"payment-service-v2",
				 "payload":{"orderNo":"%s","paymentKey":"tgen_x","method":"카드","installments":3},
				 "headers":{"x-source":"test"}}
				""".formatted(UUID.randomUUID(), orderNo, orderNo, orderNo);

		publishReply(orderNo, json);

		awaitOrder(orderNo, "COMPLETED");
	}

	@Test
	@DisplayName("같은 eventId 로 두 번 발행해도 한 번만 처리된다")
	void duplicateEventIdIsConsumedOnce() {
		String orderNo = placedOrder(5);
		int totalBefore = totalOf("P-1001");
		String eventId = UUID.randomUUID().toString();
		String json = envelope(eventId, "PAYMENT_APPROVED", orderNo, ",\"paymentKey\":\"tgen_x\"");

		publishReply(orderNo, json);
		publishReply(orderNo, json);

		awaitOrder(orderNo, "COMPLETED");
		// 두 번째 메시지가 늦게 올 수 있어 잠깐 더 지켜본다.
		await().during(Duration.ofSeconds(2)).atMost(TIMEOUT).untilAsserted(() -> {
			assertThat(totalBefore - totalOf("P-1001")).isEqualTo(5);
			assertThat(countForOrder("outbox", orderNo)).isEqualTo(2); // APPROVE_PAYMENT + ORDER_COMPLETED
		});
	}

	@Test
	@DisplayName("없는 주문의 회신이 와도 리스너가 멈추지 않는다")
	void replyForUnknownOrderDoesNotStopTheListener() {
		String orderNo = placedOrder(1);

		publishReply(orderNo, approvalOf("ORD-20260903-NOSUCH01"));
		publishReply(orderNo, approvalOf(orderNo));

		awaitOrder(orderNo, "COMPLETED");
	}
}
