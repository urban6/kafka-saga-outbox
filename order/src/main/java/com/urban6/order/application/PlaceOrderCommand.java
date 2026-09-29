package com.urban6.order.application;

import java.util.List;

/** 멱등 지문이 이 JSON 으로 계산된다. API DTO 가 바뀌어도 필드를 따라 바꾸지 않는다. */
public record PlaceOrderCommand(String customerId, List<Item> items) {

    public record Item(String productId, int quantity) {
    }
}
