package com.urban6.order.domain;

/**
 * 사가 진행 상태. {@link OrderStatus} 와 짝처럼 보이지만 관심사가 다르다.
 * <p>
 * {@code OrderStatus} 는 고객에게 보여줄 주문의 상태고, 이건 오케스트레이션의 진행 상태다.
 * Stuck Saga 탐지는 {@code idx_stuck (status, step_started_at)} 으로 이 컬럼만 보고 돈다 —
 * 주문 상태로 탐지하면 "사가가 멈춘 것"과 "주문이 원래 그 상태인 것"을 구분할 수 없다.
 */
public enum SagaStatus {

	STARTED,              // 원격 단계 회신 대기중
	COMPENSATING,         // 원격 보상(CANCEL_PAYMENT) 회신 대기중
	COMPLETED,            // 정상 종료
	CANCELED,             // 보상까지 끝나고 취소 종료
	COMPENSATION_FAILED;  // 보상 실패. 수동 개입 대상

	/** 방어선 2겹째. 종료된 사가에 늦게 도착한 회신은 여기서 걸러진다. */
	public boolean isTerminated() {
		return this == COMPLETED || this == CANCELED || this == COMPENSATION_FAILED;
	}
}
