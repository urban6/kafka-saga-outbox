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

/**
 * 사가 회신을 받아 주문·재고·사가를 전이시킨다. 전이는 이 클래스만 한다.
 * 하는 일의 대부분은 안 하는 것이다 — 중복·지연·종료된 사가를 방어선 셋으로 걸러낸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSagaOrchestrator {

	/** decide 의 반환값. 원격 단계가 결제 하나뿐이라 셋으로 끝난다. */
	enum SagaDecision {
		/** 결제 승인 → 재고 확정 + 주문 완료 */
		COMPLETE,
		/** 결제 거절 → 재고 해제 + 주문 취소 */
		COMPENSATE,
		/** 늦게 온 회신, 지나간 단계. 아무것도 하지 않는다 */
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

	/**
	 * 회신 하나를 처리한다. 멱등 선점부터 전이까지 한 트랜잭션이다.
	 * 방어선 셋 중 하나라도 걸리면 아무것도 하지 않고 돌아간다.
	 */
	@Transactional
	public void handleReply(InboundEnvelope envelope, EventType eventType) {
		String orderNo = envelope.aggregateId();

		// ── 방어선 1: 멱등 테이블. 확인과 선점 사이를 파고들 수 없게 PK 충돌에 맡긴다.
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

		// ── 방어선 2: 종료된 사가. 재처리·지연 도착은 정상이라 조용히 흘린다.
		if (saga.isTerminated()) {
			log.info("saga already terminated. orderNo={} status={} eventType={}",
					orderNo, saga.getStatus(), eventType);
			return;
		}

		// ── 방어선 3: 지금 기다리는 단계의 회신인가.
		SagaDecision decision = decide(saga.getCurrentStep(), eventType);
		if (decision == SagaDecision.IGNORE) {
			log.info("reply does not match current step. orderNo={} currentStep={} eventType={}",
					orderNo, saga.getCurrentStep(), eventType);
			return;
		}

		apply(saga, decision);
	}

	/**
	 * 현재 단계와 회신 타입만 보고 무엇을 할지 정한다. 사가 규칙의 유일한 정의이고,
	 * DB·시계를 건드리지 않는 순수 함수라 모든 조합을 단위 테스트로 덮는다.
	 */
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

	/**
	 * 결정을 영속화한다. 사가 → 재고 → 주문 순서이며 전부 한 트랜잭션이다.
	 */
	private void apply(SagaInstance saga, SagaDecision decision) {
		Instant now = Instant.now();
		String orderNo = saga.getOrderNo();

		SagaStatus nextSagaStatus =
				decision == SagaDecision.COMPLETE ? SagaStatus.COMPLETED : SagaStatus.CANCELED;

		// 사가 전이가 최종 방어선이라 먼저 한다. 방어선 3 은 읽고 나서 검사하므로 그 틈이 있다 —
		// payment 가 eventId 를 새로 발급해 같은 회신을 재발행하면 멱등 테이블로는 못 막는다.
		int moved = sagaInstanceRepository.transitionStatus(
				saga.getSagaId(), SagaStatus.STARTED, nextSagaStatus, now);
		if (moved == 0) {
			log.info("saga already moved by another handler. orderNo={}", orderNo);
			return;
		}
		// 이 시점부터 saga 인스턴스의 status 는 옛 값이다. 벌크 UPDATE 는 영속성 컨텍스트를 우회한다.

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

	/**
	 * order.events 로 나가는 외부용 이벤트. 주문 전이와 같은 트랜잭션에서 커밋된다.
	 * 전이에 성공한 뒤에만 부른다 — 안 바뀐 상태를 "완료됐다" 고 알리는 게 제일 고약하다.
	 */
	private void publishDomainEvent(Order order, SagaDecision decision, OrderStatus nextOrderStatus) {
		EventType eventType = decision == SagaDecision.COMPLETE
				? EventType.ORDER_COMPLETED
				: EventType.ORDER_CANCELED;

		// status 는 엔티티가 아니라 전이시킨 값을 쓴다. 벌크 UPDATE 는 1차 캐시를 우회한다.
		outboxWriter.append("Order", EventEnvelope.of(eventType, order.getOrderNo(),
				new OrderEventPayload(order.getOrderNo(), order.getCustomerId(),
						nextOrderStatus, order.getTotalAmount())));
	}

	/**
	 * 재고 확정 또는 해제. 예약과 같은 정렬로 상품 단위로 되돌린다.
	 * 0건은 예약이 사라졌다는 뜻이라 조용히 넘기지 않고 던져서 롤백시킨다.
	 */
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
