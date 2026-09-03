package com.urban6.order.infra.messaging;

import com.urban6.order.domain.OrderStatus;

import java.math.BigDecimal;

/**
 * Order → 외부. 주문이 끝났다는 사실만 알린다.
 *
 * 재고·사가·결제의 내부 사정은 담지 않는다. 통제하지 않는 소비자에게 나가는 계약이라
 * 한 번 실은 필드는 빼기 어렵다. SagaStatus 도 없다 — 사가는 구현 수단이지 도메인 사실이 아니다.
 *
 * @param status 엔티티가 아니라 전이시킨 값을 받는다. 조건부 UPDATE 는 1차 캐시를 우회한다
 */
public record OrderEventPayload(
		String orderNo,
		String customerId,
		OrderStatus status,
		BigDecimal totalAmount
) {
}
