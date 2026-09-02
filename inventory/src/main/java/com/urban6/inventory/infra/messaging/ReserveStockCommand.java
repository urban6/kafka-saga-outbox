package com.urban6.inventory.infra.messaging;

import java.util.List;

/**
 * {@code RESERVE_STOCK} 의 payload. Order → Inventory.
 * <p>
 * order 가 보내는 record 와 필드가 같지만 <b>계약을 공유하지 않고 자기가 선언</b>한다.
 * 여기 적는 건 이 서비스가 실제로 쓰는 필드뿐이다. order 가 나중에 payload 에 필드를 추가해도
 * (예: 배송지) 이 record 는 모르는 필드를 무시하고 그대로 동작한다.
 */
public record ReserveStockCommand(
		String orderNo,
		List<Line> lines
) {

	public record Line(String productId, int quantity) {
	}
}
