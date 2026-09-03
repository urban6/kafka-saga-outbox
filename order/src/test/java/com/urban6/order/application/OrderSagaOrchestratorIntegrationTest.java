package com.urban6.order.application;

import com.urban6.order.api.dto.PlaceOrderRequest;
import com.urban6.order.api.dto.PlaceOrderResponse;
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

/**
 * 회신 처리의 방어선 3겹과 재고 전이를 실제 DB 위에서 확인한다.
 *
 * decide() 는 단위 테스트가 전수로 덮었다. 여기서 보는 건 그 결정이 DB 에 옮겨질 때의
 * 성질이다 — 조건부 UPDATE 가 몇 건을 돌려주는가, 멱등 테이블의 PK 충돌이 실제로 두 번째를 막는가,
 * 재고가 확정과 해제에서 다르게 움직이는가. 전부 목으로는 검증되지 않는 것들이다.
 */
class OrderSagaOrchestratorIntegrationTest extends OrderIntegrationTest {

	@Autowired
	private PlaceOrderService placeOrderService;

	@Autowired
	private OrderSagaOrchestrator orchestrator;

	@Autowired
	private ObjectMapper objectMapper;

	/** 결제 승인을 기다리는 주문 하나를 만든다. 재고는 예약된 상태다. */
	private String placedOrder(String productId, int quantity) {
		PlaceOrderResponse response = placeOrderService.place(UUID.randomUUID().toString(),
				new PlaceOrderRequest("C-1", List.of(new PlaceOrderRequest.Item(productId, quantity))));
		return response.orderNo();
	}

	/**
	 * payment 가 보냈을 회신 봉투. payload 는 오케스트레이터가 읽지 않지만
	 * (결정은 eventType 과 현재 단계만으로 난다) 실제 모양을 넣어둔다.
	 */
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
		// 확정은 예약을 푸는 동시에 창고에서 뺀다. 해제와 구분되는 지점이 여기다.
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
		// 돈이 안 빠졌으니 물건도 그대로다. 완전 원복.
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

		// 두 번 확정됐다면 total 이 90 이 됐을 것이다.
		assertThat(totalOf("P-1001")).isEqualTo(95);
		assertThat(countOf("consumed_message")).isEqualTo(1);
		// 도메인 이벤트도 하나여야 한다. 둘이면 외부가 완료를 두 번 본다.
		assertThat(countOf("outbox")).isEqualTo(2); // APPROVE_PAYMENT + ORDER_COMPLETED
	}

	@Test
	@DisplayName("방어선 2 — 종료된 사가에 회신이 오면 무시한다 (eventId 가 달라도)")
	void terminatedSagaIgnoresLateReply() {
		String orderNo = placedOrder("P-1001", 2);
		orchestrator.handleReply(reply(UUID.randomUUID(), orderNo, EventType.PAYMENT_APPROVED),
				EventType.PAYMENT_APPROVED);
		assertThat(totalOf("P-1001")).isEqualTo(98);

		// payment 가 eventId 를 새로 발급해 재발행한 상황. 멱등 테이블로는 못 막는다.
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

		// 이게 통과하면 이미 받은 돈에 대해 재고가 풀린다. 종료된 사가라 방어선 2에서 걸린다.
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
