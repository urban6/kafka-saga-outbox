package com.urban6.payment.domain;

/**
 * Toss 결제 상태를 그대로 쓴다. 조회 API 응답과 어휘가 같아야 우리 상태와 PG 상태를
 * 변환 없이 맞춰볼 수 있다.
 * <p>
 * {@code READY} 는 이 프로젝트에 없다 — 결제창 대역이 인증을 마친 {@code IN_PROGRESS} 부터 시작한다.
 * 이 서비스에서 {@code IN_PROGRESS} 는 "승인 요청은 보냈는데 결과를 모른다" 는 뜻으로 쓸 예정이며,
 * 지금은 그 상태로 저장되는 경로가 없다(PG 응답을 받은 뒤에 저장하므로). in-doubt 를 다룰 때 생긴다.
 */
public enum PaymentStatus {

	READY,
	IN_PROGRESS,
	DONE,
	CANCELED,
	/** 거절됨. 재시도해도 결과가 같은 확정된 실패다. */
	ABORTED,
	EXPIRED;

	public boolean isTerminated() {
		return this == DONE || this == CANCELED || this == ABORTED || this == EXPIRED;
	}
}
