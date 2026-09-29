package com.urban6.order.application;

import com.urban6.order.domain.Order;
import com.urban6.order.domain.OrderStatus;
import com.urban6.order.domain.SagaInstance;
import com.urban6.order.domain.SagaStatus;
import com.urban6.order.domain.SagaStep;
import com.urban6.order.infra.messaging.EventEnvelope;
import com.urban6.order.infra.messaging.EventType;
import com.urban6.order.infra.messaging.IdempotencyGuard;
import com.urban6.order.infra.messaging.InboundEnvelope;
import com.urban6.order.infra.messaging.OrderEventPayload;
import com.urban6.order.infra.messaging.OutboxWriter;
import com.urban6.order.infra.persistence.OrderRepository;
import com.urban6.order.infra.persistence.ProductRepository;
import com.urban6.order.infra.persistence.SagaInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/** 사가 회신을 받아 주문·재고·사가를 전이시킨다. 전이는 이 클래스만 한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSagaOrchestrator {

	enum SagaDecision {
		COMPLETE,
		COMPENSATE,
		IGNORE
	}

	private final SagaInstanceRepository sagaInstanceRepository;
	private final OrderRepository orderRepository;
	private final ProductRepository productRepository;
	private final IdempotencyGuard idempotencyGuard;
	private final OutboxWriter outboxWriter;

	// consumed_message.consumer_group 에 들어간다. 컨슈머 그룹과 같아야 해서 상수로 박지 않는다.
	@Value("${spring.kafka.consumer.group-id}")
	private final String consumerGroup;

	@Transactional
	public void handleReply(InboundEnvelope envelope, EventType eventType) {
		String orderNo = envelope.aggregateId();

		// 방어선 1: 멱등 테이블. SELECT 대신 PK 충돌로 선점한다.
		if (!idempotencyGuard.claim(envelope.eventId(), consumerGroup, eventType)) {
			log.debug("duplicate reply ignored. eventId={} orderNo={}", envelope.eventId(), orderNo);
			return;
		}

		SagaInstance saga = sagaInstanceRepository.findByOrderNo(orderNo).orElse(null);
		if (saga == null) {
			// 사가와 outbox 가 같은 커밋이라 회신이 사가보다 먼저 올 수 없다. 우리가 보낸 적 없는 메시지다.
			log.warn("reply for unknown saga. orderNo={} eventType={}", orderNo, eventType);
			return;
		}

		// 방어선 2: 종료된 사가. 재처리·지연 도착은 정상이다.
		if (saga.isTerminated()) {
			log.info("saga already terminated. orderNo={} status={} eventType={}",
					orderNo, saga.getStatus(), eventType);
			return;
		}

		// 방어선 3: 지금 기다리는 단계의 회신인가.
		SagaDecision decision = decide(saga.getCurrentStep(), eventType);
		if (decision == SagaDecision.IGNORE) {
			log.info("reply does not match current step. orderNo={} currentStep={} eventType={}",
					orderNo, saga.getCurrentStep(), eventType);
			return;
		}

		apply(saga, decision);
	}

	/** 순수 함수. 사가 규칙의 유일한 정의다. */
	static SagaDecision decide(SagaStep currentStep, EventType eventType) {
		// SagaStep 값이 하나뿐이라 지금은 늘 통과한다. 단계가 늘면 지난 단계의 늦은 회신을 여기서 막는다.
		if (currentStep != SagaStep.APPROVE_PAYMENT) {
			return SagaDecision.IGNORE;
		}
		return switch (eventType) {
			case PAYMENT_APPROVED -> SagaDecision.COMPLETE;
			case PAYMENT_REJECTED -> SagaDecision.COMPENSATE;
			default -> SagaDecision.IGNORE;
		};
	}

	private void apply(SagaInstance saga, SagaDecision decision) {
		Instant now = Instant.now();
		String orderNo = saga.getOrderNo();

		SagaStatus nextSagaStatus =
				decision == SagaDecision.COMPLETE ? SagaStatus.COMPLETED : SagaStatus.CANCELED;

		// 사가 전이가 최종 방어선이라 먼저 한다. eventId 만 바꾼 재발행은 멱등 테이블을 통과한다.
		int moved = sagaInstanceRepository.transitionStatus(
				saga.getSagaId(), SagaStatus.STARTED, nextSagaStatus, now);
		if (moved == 0) {
			log.info("saga already moved by another handler. orderNo={}", orderNo);
			return;
		}

		Order order = orderRepository.findByOrderNo(orderNo)
				.orElseThrow(() -> new IllegalStateException("order not found. orderNo=" + orderNo));

		applyStock(order, decision, now);

		OrderStatus nextOrderStatus =
				decision == SagaDecision.COMPLETE ? OrderStatus.COMPLETED : OrderStatus.CANCELED;

		int orderMoved = orderRepository.transitionStatus(
				orderNo, OrderStatus.PENDING, nextOrderStatus, now);
		if (orderMoved == 0) {
			// 사가와 주문이 어긋났다. 조용히 넘기면 재고만 움직이고 주문이 남는다.
			throw new IllegalStateException(
					"order status mismatch. orderNo=" + orderNo + " expected=PENDING");
		}

		publishDomainEvent(order, decision, nextOrderStatus);

		log.info("saga applied. orderNo={} decision={} orderStatus={}", orderNo, decision, nextOrderStatus);
	}

	/** 주문 전이에 성공한 뒤에만 부른다. 안 바뀐 상태를 알리면 안 된다. */
	private void publishDomainEvent(Order order, SagaDecision decision, OrderStatus nextOrderStatus) {
		EventType eventType = decision == SagaDecision.COMPLETE
				? EventType.ORDER_COMPLETED
				: EventType.ORDER_CANCELED;

		// status 는 엔티티가 아니라 전이시킨 값을 쓴다. 벌크 UPDATE 는 1차 캐시를 우회한다.
		outboxWriter.append("Order", EventEnvelope.of(eventType, order.getOrderNo(),
				new OrderEventPayload(order.getOrderNo(), order.getCustomerId(),
						nextOrderStatus, order.getTotalAmount())));
	}

	/** 0건은 예약이 사라졌다는 뜻이라 던져서 롤백한다. */
	private void applyStock(Order order, SagaDecision decision, Instant now) {
		for (Map.Entry<String, Integer> entry : order.quantitiesByProduct().entrySet()) {
			String productId = entry.getKey();
			int quantity = entry.getValue();

			int updated = decision == SagaDecision.COMPLETE
					? productRepository.confirm(productId, quantity, now)
					: productRepository.release(productId, quantity, now);

			if (updated == 0) {
				throw new IllegalStateException("stock " + decision + " failed. orderNo="
						+ order.getOrderNo() + " productId=" + productId + " quantity=" + quantity);
			}
		}
	}
}
