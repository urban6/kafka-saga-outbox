package com.urban6.order.application;

/**
 * 같은 Idempotency-Key 로 다른 요청 본문이 왔다.
 * 조용히 이전 주문을 돌려주면 클라이언트 버그가 영영 안 드러나므로 거부한다.
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
