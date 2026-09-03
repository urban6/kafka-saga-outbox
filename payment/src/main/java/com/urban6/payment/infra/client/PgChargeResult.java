package com.urban6.payment.infra.client;

/**
 * PG 청구의 결론. 응답 코드가 아니라 <b>유스케이스가 알아야 하는 것</b>으로 줄인 것이다.
 * 호출부는 HTTP 상태나 Toss 에러 코드를 몰라도 된다.
 * <p>
 * 지금은 두 갈래뿐이다. 타임아웃·5xx 를 주입하기 시작하면 "재시도" 와 "모름(in-doubt)" 이
 * 여기 추가된다 — 장애가 없으면 나올 수 없는 결론이라 미리 만들지 않는다.
 *
 * @param paymentKey  승인됐을 때만 값이 있다. PG 가 확인해준 식별자
 * @param failureCode 거절 근거. {@code payment.failure_code} 에 그대로 남는다.
 *                    Toss 코드일 수도, PG 를 부르기 전에 우리가 판정한 코드({@code NO_BILLING_KEY})일 수도 있다
 */
public record PgChargeResult(
		Outcome outcome,
		String paymentKey,
		String failureCode,
		String failureMessage
) {

	public enum Outcome {
		APPROVED,
		REJECTED
	}

	public static PgChargeResult approved(String paymentKey) {
		return new PgChargeResult(Outcome.APPROVED, paymentKey, null, null);
	}

	public static PgChargeResult rejected(String failureCode, String failureMessage) {
		return new PgChargeResult(Outcome.REJECTED, null, failureCode, failureMessage);
	}

	public boolean isApproved() {
		return outcome == Outcome.APPROVED;
	}
}
