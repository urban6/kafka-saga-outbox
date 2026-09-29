package com.urban6.order.application.exception;

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
