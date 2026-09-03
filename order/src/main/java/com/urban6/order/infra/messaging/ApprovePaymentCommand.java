package com.urban6.order.infra.messaging;

import java.math.BigDecimal;

/**
 * Order → Payment. 사가의 유일한 원격 단계인 결제 승인(빌링키 청구) 요청.
 * 카드 정보나 PG 대화의 세부는 싣지 않는다 — order 가 아는 건 고객뿐이고,
 * 어느 빌링키로 청구할지는 payment 가 customerId 로 자기 저장소에서 찾는다.
 */
public record ApprovePaymentCommand(
		String orderNo,
		String customerId,
		BigDecimal amount
) {
}
