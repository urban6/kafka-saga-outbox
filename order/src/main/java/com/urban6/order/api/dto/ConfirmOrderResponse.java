package com.urban6.order.api.dto;

import com.urban6.order.domain.OrderStatus;

public record ConfirmOrderResponse(
        String orderNo,
        OrderStatus status
) {
}
