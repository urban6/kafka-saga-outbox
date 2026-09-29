package com.urban6.order.domain.exception;

import lombok.Getter;

@Getter
public class UnknownProductException extends RuntimeException {

    private final String productId;

    public UnknownProductException(String productId) {
        super("unknown product. productId=" + productId);
        this.productId = productId;
    }

}
