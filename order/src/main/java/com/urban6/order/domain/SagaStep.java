package com.urban6.order.domain;

/**
 * 사가가 지금 회신을 기다리는 원격 단계. 재고는 로컬 트랜잭션이라 단계가 아니어서 값이 하나다.
 * 값이 하나라도 enum 인 것은 current_step 과 step_started_at 이
 * "어느 단계에 얼마나 머물렀나" 를 재는 짝이기 때문이다 — Stuck 탐지가 이걸 읽는다.
 */
public enum SagaStep {

	APPROVE_PAYMENT  // 결제 승인 회신 대기
}
