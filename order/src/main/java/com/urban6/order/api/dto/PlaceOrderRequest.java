package com.urban6.order.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
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

    /**
     * 같은 상품을 여러 항목으로 나눠 보내는 걸 막는다.
     * 조용히 합치면 order_item 이 상품당 여러 행이 되고, 읽는 코드가 매번 재집계를 기억해야 한다.
     */
    // items 가 null 이면 true 다. NotEmpty 와 겹쳐 잡으면 메시지가 두 줄 나간다.
    @AssertTrue(message = "같은 상품을 여러 항목으로 나눠 보낼 수 없습니다")
    public boolean isItemsDistinct() {
        if (items == null) {
            return true;
        }
        return items.stream().map(Item::productId).distinct().count() == items.size();
    }

    public record Item(

            @NotBlank(message = "상품 ID는 필수입니다")
            @Size(max = 64, message = "상품 ID는 64자를 넘을 수 없습니다")
            String productId,

            @Positive(message = "주문 수량은 1개 이상이어야 합니다")
            int quantity
    ) {
    }
}
