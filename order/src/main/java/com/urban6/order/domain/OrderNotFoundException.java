package com.urban6.order.domain;

public class OrderNotFoundException extends RuntimeException {

    private final String orderNo;

    public OrderNotFoundException(String orderNo) {
        super("order not found. orderNo=" + orderNo);
        this.orderNo = orderNo;
    }

    public String getOrderNo() {
        return orderNo;
    }
}
