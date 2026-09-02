package com.urban6.order.domain;

public enum OrderStatus {

    CREATED,              // 생성됨
    STOCK_RESERVED,       // 재고 예약됨
    PAYMENT_APPROVED,     // 결제 승인됨
    COMPLETED,            // 주문 완료
    COMPENSATING,         // 보상 진행중
    CANCELED,             // 주문 취소됨
    COMPENSATION_FAILED;  // 보상 실패

    public boolean isTerminated() {
        return this == COMPLETED || this == CANCELED || this == COMPENSATION_FAILED;
    }
}
