package com.urban6.order.infra.messaging;

import java.math.BigDecimal;

/**
 * Order → Payment. 사가의 유일한 원격 단계인 결제 승인 요청.
 * <p>
 * PG 세부(paymentKey, 결제수단, 멱등키 헤더)는 담지 않는다. 그건 payment 서비스와 PG 사이의
 * 대화이고, order 가 알면 PG 를 교체할 때 order 까지 바뀐다. order 가 넘기는 건
 * "이 주문번호로 이 금액을 결제해라" 뿐이다.
 * <p>
 * API 요청 DTO 를 재사용하지 않는 이유는 계약의 수명이 다르기 때문이다. HTTP 요청 필드를
 * 바꿨다고 카프카 와이어 포맷이 딸려 깨지면 안 된다.
 */
public record ApprovePaymentCommand(
		String orderNo,
		BigDecimal amount
) {
}
