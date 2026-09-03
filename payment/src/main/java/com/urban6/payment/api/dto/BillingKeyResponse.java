package com.urban6.payment.api.dto;

import com.urban6.payment.domain.BillingKey;

import java.time.Instant;

/** 빌링키 자체는 내려주지 않는다. 클라이언트가 알 필요가 없고, 알면 결제 수단이 새는 경로가 하나 생긴다. */
public record BillingKeyResponse(
		String customerId,
		String cardLast4,
		Instant updatedAt
) {

	public static BillingKeyResponse from(BillingKey billingKey) {
		return new BillingKeyResponse(
				billingKey.getCustomerId(),
				billingKey.getCardLast4(),
				billingKey.getUpdatedAt());
	}
}
