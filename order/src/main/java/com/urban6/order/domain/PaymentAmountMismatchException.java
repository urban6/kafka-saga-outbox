package com.urban6.order.domain;

import java.math.BigDecimal;

/**
 * confirm 요청의 금액이 주문 금액과 다르다.
 * <p>
 * 결제창에 넘긴 금액도, confirm 으로 돌아온 금액도 브라우저를 거친 값이라 조작될 수 있다.
 * 기준은 우리 DB 의 {@code orders.total_amount} 하나뿐이고, 다르면 PG 승인 자체를 하지 않는다.
 */
public class PaymentAmountMismatchException extends RuntimeException {

    private final String orderNo;
    private final BigDecimal expected;
    private final BigDecimal received;

    public PaymentAmountMismatchException(String orderNo, BigDecimal expected, BigDecimal received) {
        super("payment amount mismatch. orderNo=" + orderNo + " expected=" + expected + " received=" + received);
        this.orderNo = orderNo;
        this.expected = expected;
        this.received = received;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public BigDecimal getExpected() {
        return expected;
    }

    public BigDecimal getReceived() {
        return received;
    }
}
