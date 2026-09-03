package com.urban6.order.domain;

/**
 * 주문 상태. 셋뿐이다.
 *
 * 주문 접수가 곧 결제 요청이라 "접수됨" 과 "결제 요청됨" 을 구분할 순간이 없고,
 * 회신 처리가 트랜잭션 하나라 PENDING 에서 곧장 COMPLETED 또는 CANCELED 로 간다.
 * 중간 상태(승인 수신, 보상 중, 보상 실패)는 회신을 기다리는 주체인 SagaStatus 쪽 개념이다 —
 * 원격 보상이 생겨도 주문은 그동안 PENDING 으로 있으면 된다.
 */
public enum OrderStatus {
    PENDING,      // 접수 + 재고 예약 + 결제 승인 커맨드 outbox 적재. 회신 대기
    COMPLETED,    // 결제 승인 → 재고 확정
    CANCELED      // 결제 거절 → 재고 해제
}
