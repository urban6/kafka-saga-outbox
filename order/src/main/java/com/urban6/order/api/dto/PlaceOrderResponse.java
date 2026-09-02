package com.urban6.order.api.dto;

import com.urban6.order.domain.OrderStatus;
import java.math.BigDecimal;

public record PlaceOrderResponse(
        String orderNo,
        OrderStatus status,
        BigDecimal totalAmount
) {
}
