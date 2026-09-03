package com.urban6.order.infra.messaging;

import java.math.BigDecimal;

/**
 * Order → Payment. 사가의 유일한 원격 단계인 결제 승인(빌링키 청구) 요청.
 * <p>
 * 카드 정보는 싣지 않는다. order 가 아는 건 고객뿐이고, 어느 빌링키로 청구할지는
 * payment 가 {@code customerId} 로 자기 저장소에서 찾는다. 결제수단이나 멱등키 헤더 같은
 * PG 대화의 세부도 담지 않는다. 그건 payment 와 PG 사이의 일이다.
 * <p>
 * API 요청 DTO 를 재사용하지 않는 이유는 계약의 수명이 다르기 때문이다. HTTP 요청 필드를
 * 바꿨다고 카프카 와이어 포맷이 딸려 깨지면 안 된다.
 */
public record ApprovePaymentCommand(
		String orderNo,
		String customerId,
		BigDecimal amount
) {
}
