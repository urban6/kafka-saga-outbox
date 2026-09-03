package com.urban6.payment.domain;

/**
 * 우리 결제 상태. Toss 어휘를 쓰되 빌링에서 실제로 도달하는 셋만 둔다 —
 * 값만 선언해두면 없는 기능이 있는 것처럼 읽힌다.
 * PG 쪽 어휘는 MockPgEngine.PgStatus 가 따로 들고 있어 여기서 겸하지 않는다.
 */
public enum PaymentStatus {

	/** 청구는 나갔는데 결과를 모른다. 복구 배치가 조회로 해소할 때까지 미완결이다. */
	IN_PROGRESS,

	/** 승인됨. 돈이 빠졌다. */
	DONE,

	/** 거절됨. 재시도해도 결과가 같은 확정된 실패다. */
	ABORTED
}
