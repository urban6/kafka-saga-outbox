package com.urban6.payment.infra.messaging;

/**
 * 회신 본문. 승인·거절을 한 record 로 쓴다.
 * <p>
 * {@code default-property-inclusion: non_null} 이라 해당 없는 필드는 JSON 에서 아예 빠진다.
 * 타입을 둘로 쪼개는 것보다 낫다 — 봉투의 {@code eventType} 이 어느 쪽인지 이미 말해주고,
 * 수신 측은 tolerant reader 라 없는 필드를 신경 쓰지 않는다.
 */
public record PaymentReplyPayload(
		String orderNo,
		String paymentKey,
		String failureCode,
		String failureReason
) {

	public static PaymentReplyPayload approved(String orderNo, String paymentKey) {
		return new PaymentReplyPayload(orderNo, paymentKey, null, null);
	}

	public static PaymentReplyPayload rejected(String orderNo, String failureCode, String failureReason) {
		return new PaymentReplyPayload(orderNo, null, failureCode, failureReason);
	}
}
