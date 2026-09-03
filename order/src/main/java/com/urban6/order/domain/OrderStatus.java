package com.urban6.order.domain;

public enum OrderStatus {

    PENDING,              // 주문서 생성 + 재고 예약. 사용자가 결제창에서 인증 중. 사가 없음
    PAYMENT_REQUESTED,    // confirm 요청 접수. 사가 시작, 결제 승인 커맨드가 outbox 에 실린 상태
    PAYMENT_APPROVED,     // 결제 승인 회신 수신
    COMPLETED,            // 재고 확정까지 끝난 주문 완료
    COMPENSATING,         // 보상 진행중 (재고 해제)
    CANCELED,             // 주문 취소됨
    COMPENSATION_FAILED;  // 보상 실패. 수동 개입 대상

    public boolean isTerminated() {
        return this == COMPLETED || this == CANCELED || this == COMPENSATION_FAILED;
    }
}
