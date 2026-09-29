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

/** 운영 진단용. 결제 상태는 없다 — order 는 payment_db 를 읽지 않는다. */
public record OrderTraceResponse(

        String orderNo,
        String customerId,
        OrderStatus status,
        BigDecimal totalAmount,
        Instant createdAt,
        Instant updatedAt,
        List<Item> items,

        /** 정상 경로에선 null 이 아니다. non_null 이라 빠지면 그 자체가 진단 정보다. */
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
