package com.urban6.payment.domain;

/** 빌링에서 실제로 도달하는 셋만 둔다. PG 어휘는 MockPgEngine.PgStatus 가 따로 든다. */
public enum PaymentStatus {

	/** 청구는 나갔는데 결과를 모른다. 복구 배치가 조회로 해소한다. */
	IN_PROGRESS,

	DONE,

	ABORTED
}
