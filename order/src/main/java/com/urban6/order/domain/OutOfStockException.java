package com.urban6.order.domain;

/**
 * 재고 부족. 요청 형식은 멀쩡하고 서버도 정상인데 지금 이 순간의 상태 때문에 실패한 것이므로
 * 400 이 아니라 409 로 응답한다. 클라이언트가 값을 고쳐서 재시도할 성질의 실패가 아니다.
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
