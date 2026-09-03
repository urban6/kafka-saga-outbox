package com.urban6.order.domain;

/**
 * 주문 상태. 셋뿐이다 — 주문 접수가 곧 결제 요청이라 둘을 구분할 순간이 없고,
 * 회신 처리가 트랜잭션 하나라 중간 상태를 아무도 관측할 수 없다.
 * 중간 상태는 SagaStatus 쪽 개념이다.
 */
public enum OrderStatus {
    PENDING,      // 접수 + 재고 예약 + 결제 승인 커맨드 outbox 적재. 회신 대기
    COMPLETED,    // 결제 승인 → 재고 확정
    CANCELED      // 결제 거절 → 재고 해제
}
