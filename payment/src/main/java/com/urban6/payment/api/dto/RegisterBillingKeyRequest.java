package com.urban6.payment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RegisterBillingKeyRequest(

		@NotBlank(message = "고객 ID는 필수입니다")
		String customerId,

		@NotBlank(message = "카드번호는 필수입니다")
		@Pattern(regexp = "\\d{16}", message = "카드번호는 숫자 16자리여야 합니다")
		String cardNumber
) {
}
