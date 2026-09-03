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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * 사가 회신을 받아 전이를 결정하는 유일한 지점.
 *
 * 이 클래스가 하는 일의 대부분은 안 하는 것이다. at-least-once 라 같은 회신이 두 번 오고,
 * 재시작하면 종료된 사가에도 회신이 도착하며, 늦게 온 회신이 이미 지나간 단계를 가리키기도 한다.
 * 방어선을 셋 두는 이유는 각각이 막는 상황이 다르기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class OrderSagaOrchestrator {

	private static final Logger log = LoggerFactory.getLogger(OrderSagaOrchestrator.class);

	/**
	 * 회신을 받았을 때 무엇을 할지. decide 의 반환값이자 이 사가가 가질 수 있는 결정의 전부다.
	 * 원격 단계가 결제 하나뿐이라 셋으로 끝난다.
	 */
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

	/**
	 * consumed_message.consumer_group 에 들어가는 값.
	 * 컨슈머 그룹과 반드시 같아야 하므로 상수로 박지 않고 같은 프로퍼티를 읽는다.
	 */
	@Value("${spring.kafka.consumer.group-id}")
	private final String consumerGroup;

	/**
	 * 멱등 선점과 이후의 모든 처리가 같은 트랜잭션이어야 한다.
	 * 분리하면 처리 중 롤백됐을 때 선점만 남아 그 메시지는 영원히 재처리되지 않는다.
	 */
	@Transactional
	public void handleReply(InboundEnvelope envelope, EventType eventType) {
		String orderNo = envelope.aggregateId();

		// ── 방어선 1: 멱등 테이블 ──────────────────────────────
		// SELECT 로 먼저 확인하지 않는다. 확인과 선점 사이를 다른 컨슈머가 파고들 수 있어서,
		// PK 충돌이라는 원자적 판정에 맡긴다.
		if (!idempotencyGuard.claim(envelope.eventId(), consumerGroup, eventType)) {
			log.debug("duplicate reply ignored. eventId={} orderNo={}", envelope.eventId(), orderNo);
			return;
		}

		SagaInstance saga = sagaInstanceRepository.findByOrderNo(orderNo).orElse(null);
		if (saga == null) {
			// 사가 INSERT 와 outbox INSERT 가 같은 커밋에 실리고 Debezium 은 커밋 이후를 읽으므로,
			// 회신이 사가보다 먼저 도착할 수는 없다. 즉 재시도로 나아질 상황이 아니라
			// 우리가 보낸 적 없는 메시지다.
			log.warn("reply for unknown saga. orderNo={} eventType={}", orderNo, eventType);
			return;
		}

		// ── 방어선 2: 종료된 사가 ─────────────────────────────
		// 재처리·지연 도착으로 끝난 주문에 회신이 오는 건 정상이다. 조용히 흘린다.
		if (saga.isTerminated()) {
			log.info("saga already terminated. orderNo={} status={} eventType={}",
					orderNo, saga.getStatus(), eventType);
			return;
		}

		// ── 방어선 3: 현재 단계와 맞는 회신인가 ─────────────────
		SagaDecision decision = decide(saga.getCurrentStep(), eventType);
		if (decision == SagaDecision.IGNORE) {
			log.info("reply does not match current step. orderNo={} currentStep={} eventType={}",
					orderNo, saga.getCurrentStep(), eventType);
			return;
		}

		apply(saga, decision);
	}

	/**
	 * 순수 함수. 현재 단계와 회신 타입만 보고 결정한다. DB·시계·랜덤을 건드리지 않으므로
	 * 스프링 컨텍스트 없이 모든 조합을 단위 테스트로 돌릴 수 있다.
	 *
	 * 여기가 사가 규칙의 유일한 정의이고, apply 는 그 결정을 DB 에 옮기기만 한다.
	 * 둘을 섞으면 "왜 이 상태가 됐는가"를 트랜잭션 코드 사이에서 찾아야 한다.
	 *
	 * 단계 검사는 SagaStep 값이 하나뿐이라 지금은 항상 통과한다. 그래도 남기는 건
	 * 불변조건이기 때문이다 — "회신은 지금 기다리는 단계의 것이어야 한다" 는 규칙이 사라지면,
	 * 단계가 하나 늘었을 때 지난 단계의 늦은 회신이 그대로 통과한다.
	 */
	static SagaDecision decide(SagaStep currentStep, EventType eventType) {
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
	 * 결정을 영속화한다. 사가 → 재고 → 주문 순서다.
	 *
	 * 사가 전이를 먼저 하는 이유는 그게 최종 방어선이기 때문이다. 방어선 3은 읽고 나서
	 * 검사하므로 그 사이에 다른 트랜잭션이 끼어들 수 있다(payment 가 eventId 를 새로 발급해
	 * 같은 승인 회신을 재발행한 경우 — 멱등 테이블로는 못 막는다).
	 * 조건부 UPDATE 가 0건이면 누가 이미 처리한 것이니 조용히 물러난다.
	 *
	 * 보상에 중간 상태를 두지 않는 이유: 재고 해제와 주문 취소뿐이고 둘 다 로컬이라 같은 트랜잭션에서
	 * 끝난다. 중간 상태를 DB 에 쓴들 그 행을 읽을 수 있는 트랜잭션이 존재하지 않는다.
	 */
	private void apply(SagaInstance saga, SagaDecision decision) {
		Instant now = Instant.now();
		String orderNo = saga.getOrderNo();

		SagaStatus nextSagaStatus =
				decision == SagaDecision.COMPLETE ? SagaStatus.COMPLETED : SagaStatus.CANCELED;

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
			// 사가는 STARTED 였는데 주문은 PENDING 이 아니다 = 둘이 어긋났다.
			// 조용히 넘어가면 재고만 움직이고 주문은 그대로 남는다. 롤백시켜야 한다.
			throw new IllegalStateException(
					"order status mismatch. orderNo=" + orderNo + " expected=PENDING");
		}

		publishDomainEvent(order, decision, nextOrderStatus);

		log.info("saga applied. orderNo={} decision={} orderStatus={}", orderNo, decision, nextOrderStatus);
	}

	/**
	 * order.events 로 나가는 외부용 이벤트. 같은 트랜잭션이라 주문 전이와 함께 커밋된다.
	 *
	 * 조건부 UPDATE 가 성공한 뒤에만 부른다. 0건이었으면 이미 예외로 롤백됐거나 물러난 뒤다 —
	 * 상태가 안 바뀌었는데 "완료됐다" 를 발행하면 그게 제일 고약한 거짓말이다.
	 *
	 * 소비자는 아직 없다. 그래도 발행하는 이유는 같은 outbox 가 토픽 둘로 갈라지는 것을
	 * 보여주기 위해서다 — 커맨드는 payment.commands, 도메인 이벤트는 order.events 로,
	 * 라우팅은 EventType 이 들고 있는 토픽이 결정한다.
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
	 * 재고 확정 또는 해제. 예약할 때 합산해서 잡았으므로 여기서도 상품 단위로 합산해 되돌린다.
	 *
	 * 0건은 있을 수 없는 상황이다 — 이 예약은 우리가 잡아둔 것이고 사가가 끝날 때까지 아무도 못 건드린다.
	 * 그래서 조용히 넘기지 않고 예외로 롤백시킨다. 멱등 선점도 같이 풀려 재처리 대상이 되고,
	 * 재시도가 다 실패하면 사가가 STARTED 로 남아 Stuck 탐지에 걸린다.
	 *
	 * 결제가 이미 승인된 뒤라면 되돌리는 게 아니라 될 때까지 미는 게 맞다(forward recovery).
	 * 돈은 받았고 물건은 창고에 있다. 여기서 결제를 자동으로 취소하지 않는 이유가 그것이다 —
	 * confirm 0건은 비즈니스 조건이 아니라 우리가 잡아둔 예약이 사라졌다는 뜻이고,
	 * 데이터가 깨진 상황에 자동 환불을 걸면 더 나쁜 일이 벌어진다.
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
