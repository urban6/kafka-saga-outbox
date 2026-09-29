package com.urban6.order.infra.messaging;

import com.urban6.order.domain.OrderStatus;

import java.math.BigDecimal;

/** 외부 계약이라 재고·사가·결제 내부 사정을 싣지 않는다. 한 번 실은 필드는 빼기 어렵다. */
public record OrderEventPayload(
		String orderNo,
		String customerId,
		OrderStatus status,
		BigDecimal totalAmount
) {
}
