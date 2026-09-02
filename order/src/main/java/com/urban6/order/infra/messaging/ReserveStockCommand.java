package com.urban6.order.infra.messaging;

import java.util.List;

/**
 * Order → Inventory. 사가 1단계인 재고 예약 요청.
 * <p>
 * API 요청 DTO 를 재사용하지 않는 이유는 계약의 수명이 다르기 때문이다. HTTP 요청 필드를
 * 바꿨다고 카프카 와이어 포맷이 딸려 깨지면 안 된다. 단가를 싣지 않는 것도 같은 이유로,
 * 수신자가 실제로 쓰는 값만 계약에 담는다.
 */
public record ReserveStockCommand(
		String orderNo,
		List<Line> lines
) {

	public record Line(String productId, int quantity) {
	}
}
