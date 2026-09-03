package com.urban6.order.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 결제창이 {@code successUrl} 로 돌려준 값 그대로다. {@code orderId} 는 경로에 있다.
 * {@code amount} 를 굳이 받는 이유는 서버 검증의 대상이기 때문이다 — 안 받으면 검증할 게 없다.
 */
public record ConfirmOrderRequest(

        @NotBlank(message = "paymentKey는 필수입니다")
        @Size(max = 200, message = "paymentKey는 200자를 넘을 수 없습니다")
        String paymentKey,

        @NotNull(message = "결제 금액은 필수입니다")
        @Positive(message = "결제 금액은 0보다 커야 합니다")
        BigDecimal amount
) {
}
