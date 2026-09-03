package com.urban6.order.application;

import com.urban6.order.api.dto.ConfirmOrderResponse;
import com.urban6.order.domain.Order;
import com.urban6.order.domain.OrderNotConfirmableException;
import com.urban6.order.domain.OrderNotFoundException;
import com.urban6.order.domain.OrderStatus;
import com.urban6.order.domain.PaymentAmountMismatchException;
import com.urban6.order.domain.SagaInstance;
import com.urban6.order.infra.messaging.ApprovePaymentCommand;
import com.urban6.order.infra.messaging.EventEnvelope;
import com.urban6.order.infra.messaging.EventType;
import com.urban6.order.infra.messaging.OutboxWriter;
import com.urban6.order.infra.persistence.OrderRepository;
import com.urban6.order.infra.persistence.SagaInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 결제 승인 요청 접수. <b>사가는 여기서 시작한다.</b>
 * <p>
 * 결제창에서 인증을 마친 프론트가 {@code paymentKey} 를 들고 오는 지점이다. Toss 는 이 시점까지
 * 돈을 빼지 않는다 — 우리가 confirm 을 호출해야 결제가 되므로, 그 전에 금액을 검증할 기회가
 * 정확히 한 번 있고 그게 이 메서드다.
 * <p>
 * 한 트랜잭션에 넷이 들어간다: 금액 검증 → {@code PENDING → PAYMENT_REQUESTED} 조건부 전이 →
 * 사가 INSERT → 커맨드 Outbox 적재. 커밋되면 binlog 에 함께 실리고 Debezium 이
 * {@code payment.commands} 로 내보낸다. 발행 여부를 애플리케이션은 알지 못한다.
 */
@Service
public class ConfirmOrderService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmOrderService.class);

    private final OrderRepository orderRepository;
    private final SagaInstanceRepository sagaInstanceRepository;
    private final OutboxWriter outboxWriter;

    public ConfirmOrderService(OrderRepository orderRepository,
                               SagaInstanceRepository sagaInstanceRepository,
                               OutboxWriter outboxWriter) {
        this.orderRepository = orderRepository;
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public ConfirmOrderResponse confirm(String orderNo, String paymentKey, BigDecimal amount) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new OrderNotFoundException(orderNo));

        // 결제창에 넘긴 금액도, 여기 온 금액도 브라우저를 거친 값이다. 기준은 우리 DB 뿐이다.
        // Toss 는 orderId 가 무엇인지 모르므로 이 검증은 우리만 할 수 있다.
        if (order.getTotalAmount().compareTo(amount) != 0) {
            log.warn("payment amount mismatch. orderNo={} expected={} received={}",
                    orderNo, order.getTotalAmount(), amount);
            throw new PaymentAmountMismatchException(orderNo, order.getTotalAmount(), amount);
        }

        // 조건부 전이가 곧 이 API 의 멱등 장치다. 재시도·더블클릭으로 같은 요청이 두 번 와도
        // 한 번만 성공하고, 그래서 사가도 하나만 시작된다. 만료 배치가 먼저 취소한 주문도 여기서 걸린다.
        Instant now = Instant.now();
        int moved = orderRepository.transitionStatus(
                orderNo, OrderStatus.PENDING, OrderStatus.PAYMENT_REQUESTED, now);
        if (moved == 0) {
            throw new OrderNotConfirmableException(orderNo, order.getStatus());
        }

        // 커맨드를 적재하기 전에 "무엇을 기다리는지"를 먼저 남긴다.
        SagaInstance saga = sagaInstanceRepository.save(SagaInstance.start(orderNo, paymentKey));

        // 금액은 요청값이 아니라 DB 값을 싣는다. compareTo 로는 같아도 scale 이 다를 수 있다.
        outboxWriter.append("Order", EventEnvelope.of(
                EventType.APPROVE_PAYMENT,
                orderNo,
                new ApprovePaymentCommand(orderNo, order.getTotalAmount(), paymentKey)
        ));

        log.info("payment requested. orderNo={} sagaId={} amount={}",
                orderNo, saga.getSagaId(), order.getTotalAmount());
        return new ConfirmOrderResponse(orderNo, OrderStatus.PAYMENT_REQUESTED);
    }
}
