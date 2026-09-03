package com.urban6.order.infra.messaging;

import com.urban6.order.domain.OrderStatus;

import java.math.BigDecimal;

/**
 * Order → 외부. 주문이 끝났다는 사실만 알린다.
 *
 * 재고·사가·결제의 내부 사정을 담지 않는다. 이건 우리가 통제하지 않는 소비자에게 나가는 계약이라,
 * 한 번 필드를 실으면 빼기가 어렵다. 재고 예약 수량이나 paymentKey 를 여기 실으면
 * 외부 서비스가 우리 내부 구조에 붙어버린다.
 *
 * 같은 이유로 SagaStatus 도 없다. 사가는 우리의 구현 수단이지 도메인 사실이 아니다.
 * 밖에서 알아야 하는 것은 "이 주문이 완료됐다 / 취소됐다" 뿐이다.
 *
 * 언제 일어났는지는 봉투의 occurredAt 에 이미 있다. 중복해서 담지 않는다.
 *
 * @param status 발행 시점의 확정 상태. 엔티티에서 읽지 않고 전이시킨 값을 그대로 받는다 —
 *               조건부 UPDATE 는 영속성 컨텍스트를 우회해서 엔티티가 옛 값을 들고 있다
 */
public record OrderEventPayload(
		String orderNo,
		String customerId,
		OrderStatus status,
		BigDecimal totalAmount
) {
}
