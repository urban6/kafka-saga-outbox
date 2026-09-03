package com.urban6.order.domain;

/**
 * 재고 부족. 요청도 서버도 멀쩡하고 지금 이 순간의 상태 때문에 실패한 것이라 400 이 아니라 409 다.
 */
public class OutOfStockException extends RuntimeException {

    private final String productId;
    private final int requestedQuantity;

    public OutOfStockException(String productId, int requestedQuantity) {
        super("out of stock. productId=" + productId + " requested=" + requestedQuantity);
        this.productId = productId;
        this.requestedQuantity = requestedQuantity;
    }

    public String getProductId() {
        return productId;
    }

    public int getRequestedQuantity() {
        return requestedQuantity;
    }
}
