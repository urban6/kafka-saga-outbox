package com.urban6.order.infra.messaging;

import java.math.BigDecimal;

public record ApprovePaymentCommand(
		String orderNo,
		String customerId,
		BigDecimal amount
) {
}
