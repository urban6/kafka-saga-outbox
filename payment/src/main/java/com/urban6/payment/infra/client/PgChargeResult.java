package com.urban6.payment.infra.client;

/** PG 청구의 결론. 네 갈래를 가르는 기준은 하나다 — 돈이 빠졌는가. */
public record PgChargeResult(
		Outcome outcome,
		String paymentKey,
		String failureCode,
		String failureMessage
) {

	public enum Outcome {
		APPROVED,
		REJECTED,
		RETRYABLE,
		/** 빠졌는지 모른다. 재청구 금지 — 조회로 먼저 확인한다. */
		IN_DOUBT
	}

	public static PgChargeResult approved(String paymentKey) {
		return new PgChargeResult(Outcome.APPROVED, paymentKey, null, null);
	}

	public static PgChargeResult rejected(String failureCode, String failureMessage) {
		return new PgChargeResult(Outcome.REJECTED, null, failureCode, failureMessage);
	}

	public static PgChargeResult retryable(String failureCode, String failureMessage) {
		return new PgChargeResult(Outcome.RETRYABLE, null, failureCode, failureMessage);
	}

	public static PgChargeResult inDoubt(String failureCode, String failureMessage) {
		return new PgChargeResult(Outcome.IN_DOUBT, null, failureCode, failureMessage);
	}

	public boolean isApproved() {
		return outcome == Outcome.APPROVED;
	}

	public boolean isRetryable() {
		return outcome == Outcome.RETRYABLE;
	}

	public boolean isSettled() {
		return outcome == Outcome.APPROVED || outcome == Outcome.REJECTED;
	}
}
