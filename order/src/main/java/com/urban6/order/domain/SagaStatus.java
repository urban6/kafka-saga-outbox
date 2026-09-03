package com.urban6.order.domain;

/**
 * 사가 진행 상태. OrderStatus 와 짝처럼 보이지만 관심사가 다르다 —
 * 저쪽은 고객에게 보여줄 주문의 상태고 이쪽은 오케스트레이션의 진행 상태다.
 * Stuck 탐지는 idx_stuck (status, step_started_at) 으로 이 컬럼만 보고 돈다.
 */
public enum SagaStatus {

	STARTED,    // 원격 단계 회신 대기중
	COMPLETED,  // 정상 종료
	CANCELED;   // 보상까지 끝나고 취소 종료

	/**
	 * 방어선 2겹째. 종료된 사가에 늦게 도착한 회신을 여기서 거른다.
	 * 보상 중 같은 중간 상태가 없는 건 로컬 보상이 한 트랜잭션에서 끝나 관측할 수 없어서다.
	 */
	public boolean isTerminated() {
		return this == COMPLETED || this == CANCELED;
	}
}
