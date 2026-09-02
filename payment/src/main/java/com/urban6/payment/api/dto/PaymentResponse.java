package com.urban6.payment.api.dto;

import com.urban6.payment.domain.Payment;
import com.urban6.payment.domain.PaymentStatus;

import java.math.BigDecimal;

public record PaymentResponse(
		String orderNo,
		PaymentStatus status,
		BigDecimal amount,
		String paymentKey,
		String failureCode,
		String failureReason
) {

	public static PaymentResponse from(Payment payment) {
		return new PaymentResponse(
				payment.getOrderNo(),
				payment.getStatus(),
				payment.getAmount(),
				payment.getPaymentKey(),
				payment.getFailureCode(),
				payment.getFailureReason());
	}
}
