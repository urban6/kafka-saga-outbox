package com.urban6.order.application;

import com.urban6.order.api.dto.PlaceOrderResponse;
import com.urban6.order.application.PlaceOrderCommand;
import com.urban6.order.infra.messaging.EventType;
import com.urban6.order.infra.messaging.InboundEnvelope;
import com.urban6.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class OrderSagaOrchestratorIntegrationTest extends OrderIntegrationTest {

	@Autowired
	private PlaceOrderService placeOrderService;

	@Autowired
	private OrderSagaOrchestrator orchestrator;

	@Autowired
	private ObjectMapper objectMapper;

	private String placedOrder(String productId, int quantity) {
		PlaceOrderResponse response = placeOrderService.place(UUID.randomUUID().toString(),
				new PlaceOrderCommand("C-1", List.of(new PlaceOrderCommand.Item(productId, quantity))));
		return response.orderNo();
	}

	private InboundEnvelope reply(UUID eventId, String orderNo, EventType eventType) {
		return new InboundEnvelope(
				eventId,
				eventType.name(),
				1,
				orderNo,
				orderNo,
				Instant.now(),
				objectMapper.readTree("{\"orderNo\":\"" + orderNo + "\",\"paymentKey\":\"tgen_test\"}"),
				Map.of());
	}

	private String statusOfOrder(String orderNo) {
		return jdbcTemplate.queryForObject("select status from orders where order_no = ?", String.class, orderNo);
	}

	private String statusOfSaga(String orderNo) {
		return jdbcTemplate.queryForObject(
				"select status from saga_instance where order_no = ?", String.class, orderNo);
	}

	private String eventTypeOnEvents(String orderNo) {
		return jdbcTemplate.queryForObject(
				"select event_type from outbox where aggregate_id = ? and topic = 'order.events'",
				String.class, orderNo);
	}

	@Test
	@DisplayName("승인 회신 → 재고 확정. 예약이 풀리면서 실제 수량이 빠진다")
	void approvalConfirmsStockAndCompletesOrder() {
		String orderNo = placedOrder("P-1001", 3);
		assertThat(reservedOf("P-1001")).isEqualTo(3);

		orchestrator.handleReply(reply(UUID.randomUUID(), orderNo, EventType.PAYMENT_APPROVED),
				EventType.PAYMENT_APPROVED);

		assertThat(statusOfOrder(orderNo)).isEqualTo("COMPLETED");
		assertThat(statusOfSaga(orderNo)).isEqualTo("COMPLETED");
		assertThat(reservedOf("P-1001")).isZero();
		assertThat(totalOf("P-1001")).isEqualTo(97);
		assertThat(eventTypeOnEvents(orderNo)).isEqualTo("ORDER_COMPLETED");
	}

	@Test
	@DisplayName("거절 회신 → 재고 해제. 예약만 풀리고 수량은 그대로다")
	void rejectionReleasesStockAndCancelsOrder() {
		String orderNo = placedOrder("P-1002", 4);

		orchestrator.handleReply(reply(UUID.randomUUID(), orderNo, EventType.PAYMENT_REJECTED),
				EventType.PAYMENT_REJECTED);

		assertThat(statusOfOrder(orderNo)).isEqualTo("CANCELED");
		assertThat(statusOfSaga(orderNo)).isEqualTo("CANCELED");
		assertThat(reservedOf("P-1002")).isZero();
		assertThat(totalOf("P-1002")).isEqualTo(100);
		assertThat(eventTypeOnEvents(orderNo)).isEqualTo("ORDER_CANCELED");
	}

	@Test
	@DisplayName("방어선 1 — 같은 eventId 가 두 번 오면 두 번째는 아무 일도 하지 않는다")
	void duplicateEventIdIsIgnored() {
		String orderNo = placedOrder("P-1001", 5);
		UUID eventId = UUID.randomUUID();

		orchestrator.handleReply(reply(eventId, orderNo, EventType.PAYMENT_APPROVED), EventType.PAYMENT_APPROVED);
		orchestrator.handleReply(reply(eventId, orderNo, EventType.PAYMENT_APPROVED), EventType.PAYMENT_APPROVED);

		assertThat(totalOf("P-1001")).isEqualTo(95);
		assertThat(countOf("consumed_message")).isEqualTo(1);
		assertThat(countOf("outbox")).isEqualTo(2); // APPROVE_PAYMENT + ORDER_COMPLETED
	}

	@Test
	@DisplayName("방어선 2 — 종료된 사가에 회신이 오면 무시한다 (eventId 가 달라도)")
	void terminatedSagaIgnoresLateReply() {
		String orderNo = placedOrder("P-1001", 2);
		orchestrator.handleReply(reply(UUID.randomUUID(), orderNo, EventType.PAYMENT_APPROVED),
				EventType.PAYMENT_APPROVED);
		assertThat(totalOf("P-1001")).isEqualTo(98);

		// eventId 가 달라 멱등 테이블로는 못 막는다.
		orchestrator.handleReply(reply(UUID.randomUUID(), orderNo, EventType.PAYMENT_APPROVED),
				EventType.PAYMENT_APPROVED);

		assertThat(totalOf("P-1001")).isEqualTo(98);
		assertThat(statusOfOrder(orderNo)).isEqualTo("COMPLETED");
	}

	@Test
	@DisplayName("방어선 3 — 승인 뒤 도착한 거절 회신이 재고를 되돌리지 못한다")
	void rejectionAfterApprovalDoesNotReleaseStock() {
		String orderNo = placedOrder("P-1001", 2);
		orchestrator.handleReply(reply(UUID.randomUUID(), orderNo, EventType.PAYMENT_APPROVED),
				EventType.PAYMENT_APPROVED);

		orchestrator.handleReply(reply(UUID.randomUUID(), orderNo, EventType.PAYMENT_REJECTED),
				EventType.PAYMENT_REJECTED);

		assertThat(statusOfOrder(orderNo)).isEqualTo("COMPLETED");
		assertThat(reservedOf("P-1001")).isZero();
		assertThat(totalOf("P-1001")).isEqualTo(98);
	}

	@Test
	@DisplayName("없는 주문의 회신은 예외 없이 흘려보낸다 — 던지면 재시도만 반복하다 버려진다")
	void replyForUnknownSagaIsSwallowed() {
		assertThatCode(() -> orchestrator.handleReply(
				reply(UUID.randomUUID(), "ORD-20260903-NOSUCH01", EventType.PAYMENT_APPROVED),
				EventType.PAYMENT_APPROVED))
				.doesNotThrowAnyException();

		assertThat(countOf("orders")).isZero();
	}
}
