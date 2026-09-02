package com.urban6.order.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PlaceOrderRequest(

        @NotBlank(message = "고객 ID는 필수입니다")
        @Size(max = 64, message = "고객 ID는 64자를 넘을 수 없습니다")
        String customerId,

        @NotEmpty(message = "주문 항목은 최소 1개 이상이어야 합니다")
        @Size(max = 50, message = "주문 항목은 최대 50개까지 가능합니다")
        @Valid
        List<Item> items
) {

    public record Item(

            @NotBlank(message = "상품 ID는 필수입니다")
            @Size(max = 64, message = "상품 ID는 64자를 넘을 수 없습니다")
            String productId,

            @Positive(message = "주문 수량은 1개 이상이어야 합니다")
            int quantity
    ) {
    }
}
