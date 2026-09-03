package com.urban6.order.infra.messaging;

import com.urban6.order.api.dto.PlaceOrderRequest;
import com.urban6.order.application.PlaceOrderService;
import com.urban6.order.support.OrderKafkaIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 리스너가 <b>무엇을 견디는가</b>. 여기서 지키는 명제는 하나다 —
 * <b>메시지 하나가 파티션 하나를 멈추게 하지 않는다.</b>
 * <p>
 * 이게 깨지면 같은 파티션에 실린 다른 주문들이 전부 함께 멈춘다. 실패의 범위가 메시지 하나가 아니다.
 * 그래서 나쁜 메시지와 정상 메시지에 <b>같은 키</b>를 써 같은 파티션으로 보낸다 —
 * 키가 다르면 다른 파티션으로 흩어져 "막지 않았다" 를 증명하지 못한다.
 */
class SagaReplyListenerIntegrationTest extends OrderKafkaIntegrationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	@Autowired
	private PlaceOrderService placeOrderService;

	/** 결제 승인을 기다리는 주문 하나. 재고는 예약된 상태다. */
	private String placedOrder(int quantity) {
		return placeOrderService.place(UUID.randomUUID().toString(),
				new PlaceOrderRequest("C-1", List.of(new PlaceOrderRequest.Item("P-1001", quantity))))
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

		// 역직렬화 자체가 불가능한 값. ErrorHandlingDeserializer 가 없으면 여기서 무한 재시도가 돈다.
		publishReply(orderNo, "{ this is not json");
		publishReply(orderNo, approvalOf(orderNo));

		// 같은 키라 같은 파티션이고 순서도 보장된다. 완료됐다는 건 앞 메시지를 넘어갔다는 뜻이다.
		awaitOrder(orderNo, "COMPLETED");
	}

	@Test
	@DisplayName("모르는 eventType 은 무시되고 다음 메시지가 정상 처리된다")
	void unknownEventTypeIsIgnored() {
		String orderNo = placedOrder(1);

		// payment 가 회신 종류를 추가한 상황. fromWire 덕분에 예외 없이 지나간다.
		// (valueOf 였다면 에러 핸들러가 4회 시도한 뒤에야 넘어간다 — 결국 처리는 되지만 그만큼 밀린다)
		publishReply(orderNo, envelope(UUID.randomUUID().toString(), "PAYMENT_PARTIALLY_REFUNDED", orderNo, ""));
		publishReply(orderNo, approvalOf(orderNo));

		awaitOrder(orderNo, "COMPLETED");
	}

	@Test
	@DisplayName("모르는 필드가 섞여 있어도 읽는다 — tolerant reader")
	void toleratesUnknownFields() {
		String orderNo = placedOrder(1);

		// 프로듀서가 봉투와 payload 양쪽에 필드를 늘려도 컨슈머는 그대로 돌아야 한다.
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
		String eventId = UUID.randomUUID().toString();
		String json = envelope(eventId, "PAYMENT_APPROVED", orderNo, ",\"paymentKey\":\"tgen_x\"");

		publishReply(orderNo, json);
		publishReply(orderNo, json);

		awaitOrder(orderNo, "COMPLETED");
		// 두 번 확정됐다면 total 이 90 이 됐을 것이다. 멱등 테이블이 두 번째를 막는다.
		await().atMost(TIMEOUT).untilAsserted(() -> {
			assertThat(countOf("consumed_message")).isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject(
					"select total_quantity from product where product_id = 'P-1001'", Integer.class))
					.isEqualTo(95);
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
