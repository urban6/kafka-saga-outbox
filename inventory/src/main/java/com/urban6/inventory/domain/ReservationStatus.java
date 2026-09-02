package com.urban6.inventory.domain;

/**
 * 재고 예약의 상태.
 * <p>
 * 이건 서비스 경계를 넘지 않는 내부 상태라 enum 이어도 안전하다. 카프카로 오가는
 * {@code EventTypes} 가 String 인 것과 구분한다 — 모르는 값이 밖에서 들어올 일이 없다.
 */
public enum ReservationStatus {

	/** 예약됨. 아직 되돌릴 수 있다. */
	RESERVED,

	/** 확정됨. 결제까지 끝나 되돌릴 수 없다. */
	CONFIRMED,

	/** 해제됨. 보상 트랜잭션으로 취소됐다. */
	RELEASED
}
