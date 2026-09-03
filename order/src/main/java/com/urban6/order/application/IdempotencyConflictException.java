package com.urban6.order.application;

/**
 * 같은 Idempotency-Key 로 다른 요청 본문이 왔다. 클라이언트가 키를 재사용한 것이다.
 *
 * 조용히 이전 주문을 돌려주면 "다른 주문을 넣었는데 옛날 주문번호가 돌아오는" 상태가 되어
 * 클라이언트 버그를 아무도 발견하지 못한다. 거부해서 드러내는 쪽이 낫다.
 */
public class IdempotencyConflictException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyConflictException(String idempotencyKey) {
        super("idempotency key reused with a different request body: " + idempotencyKey);
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
