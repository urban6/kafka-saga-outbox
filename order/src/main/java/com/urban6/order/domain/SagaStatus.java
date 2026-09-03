package com.urban6.order.domain;

/**
 * 사가 진행 상태. {@link OrderStatus} 와 짝처럼 보이지만 관심사가 다르다.
 * <p>
 * {@code OrderStatus} 는 고객에게 보여줄 주문의 상태고, 이건 오케스트레이션의 진행 상태다.
 * Stuck Saga 탐지는 {@code idx_stuck (status, step_started_at)} 으로 이 컬럼만 보고 돈다 —
 * 주문 상태로 탐지하면 "사가가 멈춘 것"과 "주문이 원래 그 상태인 것"을 구분할 수 없다.
 */
public enum SagaStatus {

	STARTED,    // 원격 단계 회신 대기중
	COMPLETED,  // 정상 종료
	CANCELED;   // 보상까지 끝나고 취소 종료

	/**
	 * 방어선 2겹째. 종료된 사가에 늦게 도착한 회신은 여기서 걸러진다.
	 * <p>
	 * <b>진행 중은 {@code STARTED} 하나뿐</b>이라 여집합으로 봐도 된다. 보상 상태를 두지 않은 이유는
	 * 보상이 재고 해제와 주문 취소뿐이고 둘 다 로컬이라 <b>같은 트랜잭션에서 끝나기</b> 때문이다 —
	 * 중간 상태를 DB 에 써봐야 그 행을 읽을 수 있는 트랜잭션이 존재하지 않는다.
	 * 원격 보상이 생기면 그때 회신을 기다리는 상태가 실제로 관측 가능해지므로, 그때 추가한다.
	 */
	public boolean isTerminated() {
		return this == COMPLETED || this == CANCELED;
	}
}
