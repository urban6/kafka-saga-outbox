package com.urban6.payment.infra.messaging;

/** 승인·거절을 한 record 로 쓴다. default-property-inclusion: non_null 에 의존한다 — 해당 없는 필드가 빠져야 한다. */
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
