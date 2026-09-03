package com.urban6.payment.domain;

/**
 * Toss 결제 상태를 그대로 쓴다. 조회 API 응답과 어휘가 같아야 우리 상태와 PG 상태를
 * 변환 없이 맞춰볼 수 있다.
 *
 * 빌링에서 실제로 도달하는 건 셋이다. 인증 단계가 없어 청구 한 번이 시작이자 끝이라
 * READY 를 지날 일이 없고, 취소·만료 API 는 이 프로젝트 범위 밖이다.
 * IN_PROGRESS 는 "청구는 보냈는데 돈이 빠졌는지 모른다"(in-doubt) 는 뜻으로만 쓰이며,
 * 복구 배치가 조회로 DONE 또는 ABORTED 로 옮긴다.
 *
 * 도달하지 않는 값을 남겨두지 않는 이유는 CANCEL_PAYMENT 를 지운 것과 같다 —
 * 값만 선언해두면 없는 기능이 있는 것처럼 읽힌다. PG 쪽 어휘는 MockPgEngine.PgStatus 와
 * PgClient.reconcile() 이 따로 들고 있어 여기서 겸할 필요가 없다.
 */
public enum PaymentStatus {

	/** 청구는 나갔는데 결과를 모른다. 복구 배치가 조회로 해소할 때까지 미완결이다. */
	IN_PROGRESS,

	/** 승인됨. 돈이 빠졌다. */
	DONE,

	/** 거절됨. 재시도해도 결과가 같은 확정된 실패다. */
	ABORTED
}
