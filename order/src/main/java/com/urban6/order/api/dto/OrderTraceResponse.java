package com.urban6.order.api.dto;

import com.urban6.order.domain.Order;
import com.urban6.order.domain.OrderItem;
import com.urban6.order.domain.OrderStatus;
import com.urban6.order.domain.SagaInstance;
import com.urban6.order.domain.SagaStatus;
import com.urban6.order.domain.SagaStep;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 주문 한 건의 전 구간. 운영 진단용이다.
 * 결제 상태는 없다 — order 는 payment_db 를 읽지 않는다. 필요하면 payment API 를 따로 친다.
 */
public record OrderTraceResponse(

        String orderNo,
        String customerId,
        OrderStatus status,
        BigDecimal totalAmount,
        Instant createdAt,
        Instant updatedAt,
        List<Item> items,

        /**
         * 주문과 사가는 한 트랜잭션이라 정상 경로에선 null 이 나오지 않는다.
         * non_null 이라 그때는 블록이 통째로 빠지는데, 빠졌다는 사실 자체가 진단 정보다.
         */
        Saga saga
) {

    public record Item(String productId, int quantity, BigDecimal unitPrice, BigDecimal subtotal) {

        static Item from(OrderItem item) {
            return new Item(item.getProductId(), item.getQuantity(),
                    item.getUnitPrice(), item.subtotal());
        }
    }

    public record Saga(

            UUID sagaId,
            SagaStep currentStep,
            SagaStatus status,
            Instant stepStartedAt,

            /** 이 단계에 머문 시간. 서버가 계산해준다. Stuck 탐지 배치의 임계값과 같은 값이다. */
            long stepElapsedSeconds,

            Map<String, Object> payload
    ) {

        static Saga from(SagaInstance saga, Instant now) {
            return new Saga(
                    saga.getSagaId(),
                    saga.getCurrentStep(),
                    saga.getStatus(),
                    saga.getStepStartedAt(),
                    Duration.between(saga.getStepStartedAt(), now).toSeconds(),
                    saga.getPayload());
        }
    }

    /**
     * @param saga null 가능. 위 필드 주석 참고
     * @param now  경과 시간 기준 시각. 파라미터로 받아야 이 매핑이 순수 함수로 남는다
     */
    public static OrderTraceResponse from(Order order, SagaInstance saga, Instant now) {
        return new OrderTraceResponse(
                order.getOrderNo(),
                order.getCustomerId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getItems().stream().map(Item::from).toList(),
                saga == null ? null : Saga.from(saga, now));
    }
}
