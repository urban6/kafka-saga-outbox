package com.urban6.payment.infra.client;

/**
 * PG 청구의 결론. 응답 코드가 아니라 유스케이스가 알아야 하는 것으로 줄인 것이다.
 * 호출부는 HTTP 상태나 Toss 에러 코드를 몰라도 된다.
 *
 * 네 갈래를 가르는 기준은 하나다 — 돈이 빠졌는가.
 * 승인/거절은 답을 알고, RETRYABLE 은 "안 빠진 게 확실",
 * IN_DOUBT 는 "모른다" 다. 모름을 거절로 접으면 이미 받은 돈에 보상이 돌고,
 * 재시도로 접으면 이중 결제가 난다. 그래서 셋이 아니라 넷이다.
 *
 * @param paymentKey  승인됐을 때만 값이 있다. PG 가 확인해준 식별자
 * @param failureCode 승인이 아닐 때의 근거 코드. payment.failure_code 에 그대로 남는다.
 *                    Toss 코드일 수도, 우리가 판정한 코드(NO_BILLING_KEY, PG_TIMEOUT)일 수도 있다
 */
public record PgChargeResult(
		Outcome outcome,
		String paymentKey,
		String failureCode,
		String failureMessage
) {

	public enum Outcome {
		/** 돈이 빠졌다. 확정. */
		APPROVED,
		/** 돈이 안 빠졌고 재시도해도 같다. 확정된 실패 — 사가는 보상으로 간다. */
		REJECTED,
		/** 돈이 안 빠진 게 확실하고 재시도하면 달라질 수 있다. 재청구가 안전하다. */
		RETRYABLE,
		/** 빠졌는지 모른다. 재청구 금지 — 조회로 실제 상태를 먼저 확인해야 한다. */
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

	/** 결과를 DB 에 확정할 수 있는가. 아니면 나중에 다시 봐야 한다. */
	public boolean isSettled() {
		return outcome == Outcome.APPROVED || outcome == Outcome.REJECTED;
	}
}
