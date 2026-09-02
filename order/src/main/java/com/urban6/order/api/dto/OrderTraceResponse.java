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
 * 주문 한 건의 전 구간. <b>운영 진단용</b>이다.
 * <p>
 * 결제 상태는 없다 — order 서비스는 {@code payment_db} 를 읽지 않는다.
 * 필요하면 {@code GET /api/payments/{orderNo}} 를 따로 친다. 한 화면에 합치려면 BFF 를 두거나
 * {@code order.events} 를 구독하는 조회 전용 프로젝션이 필요하고, 그게 서비스가 남의 DB 를
 * 직접 읽는 것보다 비싼 대신 독립 배포를 지킨다.
 * <p>
 * {@code saga} 블록은 실무라면 {@code /api/admin} 으로 갈라야 한다. 고객에게 보여줄 정보가 아니다.
 * 이 프로젝트는 인증이 범위 밖이라 나누는 시늉만 하게 되므로 한 응답에 두되,
 * <b>블록을 분리해</b> 나중에 자르기 쉽게 해둔다.
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
         * 사가가 없을 수 있다. 재고를 inventory 서비스가 갖고 있던 시절의 주문이 그렇다.
         * {@code default-property-inclusion: non_null} 이라 그럴 땐 이 블록이 통째로 빠지는데,
         * <b>빠졌다는 사실 자체가 진단 정보</b>다 — 커맨드를 기다리는 주체가 없는 주문이다.
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

            /**
             * 이 단계에 머문 시간. <b>서버가 계산해서 준다.</b>
             * Stuck 여부를 보려고 응답 시각과 {@code stepStartedAt} 을 사람이 빼고 있으면
             * 진단 도구가 아니다. Stuck 탐지 배치가 쓸 임계값과 같은 값이다.
             */
            long stepElapsedSeconds,

            int retryCount,
            String lastError,
            Map<String, Object> payload
    ) {

        static Saga from(SagaInstance saga, Instant now) {
            return new Saga(
                    saga.getSagaId(),
                    saga.getCurrentStep(),
                    saga.getStatus(),
                    saga.getStepStartedAt(),
                    Duration.between(saga.getStepStartedAt(), now).toSeconds(),
                    saga.getRetryCount(),
                    saga.getLastError(),
                    saga.getPayload());
        }
    }

    /**
     * @param saga {@code null} 가능. 위 필드 주석 참고
     * @param now  경과 시간 기준 시각. 파라미터로 받아 {@code Instant.now()} 를 이 안에서 부르지 않는다 —
     *             그래야 이 매핑이 순수 함수로 남아 테스트에서 시각을 고정할 수 있다
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
