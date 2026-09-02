package com.urban6.inventory.infra.messaging;

/**
 * 사가에서 오가는 메시지 종류.
 * <p>
 * <b>enum 이 아니라 String 상수인 이유</b>: 컨슈머가 모르는 타입을 받았을 때 enum 은 역직렬화
 * 단계에서 예외를 던진다. 그 메시지는 재시도해도 계속 실패하므로 파티션 전체가 막힌다.
 * String 이면 모르는 값도 일단 받아들이고 리스너가 조용히 무시할 수 있다.
 * <p>
 * 여기 값들은 order 서비스의 {@code EventType} 과 같은 문자열이지만 <b>공유하지 않고 각자 갖는다</b>.
 * 계약을 공유하면 한 서비스가 타입을 추가할 때 다른 서비스가 재빌드해야 해서 독립 배포가 깨진다.
 */
public final class EventTypes {

	// ── 수신: Order → Inventory ───────────────────────────
	public static final String RESERVE_STOCK = "RESERVE_STOCK";
	public static final String CONFIRM_STOCK = "CONFIRM_STOCK";
	public static final String RELEASE_STOCK = "RELEASE_STOCK";

	// ── 발행: Inventory → Order ───────────────────────────
	public static final String STOCK_RESERVED = "STOCK_RESERVED";
	public static final String STOCK_REJECTED = "STOCK_REJECTED";
	public static final String STOCK_CONFIRMED = "STOCK_CONFIRMED";
	public static final String STOCK_RELEASED = "STOCK_RELEASED";

	private EventTypes() {
	}
}
