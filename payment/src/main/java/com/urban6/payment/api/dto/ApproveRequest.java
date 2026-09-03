package com.urban6.payment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record ApproveRequest(

		@NotBlank(message = "주문번호는 필수입니다")
		String orderNo,

		@NotBlank(message = "paymentKey는 필수입니다")
		String paymentKey,

		@NotNull(message = "결제 금액은 필수입니다")
		@Positive(message = "결제 금액은 0보다 커야 합니다")
		BigDecimal amount
) {
}
