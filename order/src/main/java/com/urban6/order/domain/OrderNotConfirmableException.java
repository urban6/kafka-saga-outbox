package com.urban6.order.domain;

/**
 * {@code PENDING} 이 아닌 주문에 confirm 이 왔다. 이미 confirm 됐거나(재시도·더블클릭),
 * 만료·취소됐거나, 끝난 주문이다. {@link OutOfStockException} 과 같은 이유로 409 다.
 */
public class OrderNotConfirmableException extends RuntimeException {

    private final String orderNo;
    private final OrderStatus status;

    public OrderNotConfirmableException(String orderNo, OrderStatus status) {
        super("order not confirmable. orderNo=" + orderNo + " status=" + status);
        this.orderNo = orderNo;
        this.status = status;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public OrderStatus getStatus() {
        return status;
    }
}
