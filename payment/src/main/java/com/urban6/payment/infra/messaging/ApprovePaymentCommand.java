package com.urban6.payment.infra.messaging;

import java.math.BigDecimal;

public record ApprovePaymentCommand(
		String orderNo,
		String customerId,
		BigDecimal amount
) {
}
