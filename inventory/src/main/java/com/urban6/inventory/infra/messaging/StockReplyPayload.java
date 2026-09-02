package com.urban6.inventory.infra.messaging;

import java.util.List;

/**
 * {@code STOCK_RESERVED} / {@code STOCK_REJECTED} 의 payload.
 * <p>
 * 성공이면 {@code reason} 이 null 이고, 거부면 {@code reservationIds} 가 비어 있다.
 * 직렬화 시 null 필드는 빠진다({@code default-property-inclusion: non_null}).
 */
public record StockReplyPayload(
		String orderNo,
		List<String> reservationIds,
		String reason
) {

	public static StockReplyPayload reserved(String orderNo, List<String> reservationIds) {
		return new StockReplyPayload(orderNo, reservationIds, null);
	}

	public static StockReplyPayload rejected(String orderNo, String reason) {
		return new StockReplyPayload(orderNo, List.of(), reason);
	}
}
