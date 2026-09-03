package com.urban6.order.application;

import java.util.List;

/**
 * 주문 접수 유스케이스의 입력. HTTP 요청 DTO 를 그대로 받지 않는 이유는 멱등 지문 때문이다.
 *
 * requestHash 가 이 객체의 JSON 으로 계산되므로, API 표현을 바꿔도 여기 필드를 바꾸지 않는 한
 * 이미 저장된 api_idempotency.request_hash 가 그대로 유효하다. 요청 DTO 로 계산하면
 * JSON 표현을 바꾸는 순간 저장된 지문이 전부 어긋나 재시도가 신규 주문으로 통과한다.
 *
 * 필드 이름과 선언 순서는 PlaceOrderRequest 와 같다. 도입 시점의 해시를 보존하기 위한 것이고,
 * 앞으로 API 쪽이 바뀔 때 여기를 따라 바꾸면 안 된다 — 그러면 분리한 의미가 없다.
 */
public record PlaceOrderCommand(String customerId, List<Item> items) {

    public record Item(String productId, int quantity) {
    }
}
