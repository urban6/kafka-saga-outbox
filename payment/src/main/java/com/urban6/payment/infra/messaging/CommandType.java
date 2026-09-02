package com.urban6.payment.infra.messaging;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code payment.commands} 로 들어오는 커맨드 종류. <b>토픽을 들고 있지 않다</b> — 받기만 한다.
 * <p>
 * 발행용 {@link EventType} 과 분리한 이유가 그것이다. 하나로 합치면 "발행하지 않는 타입이
 * 발행 토픽을 들고 있는" 상태가 된다.
 * <p>
 * 와이어 문자열을 enum 으로 바꾸는 지점을 {@link #fromWire} 하나로 좁혔다. 모르는 값이 와도
 * 예외 대신 빈 값이 나오므로, order 가 커맨드를 추가해도 이 컨슈머의 파티션이 막히지 않는다.
 */
public enum CommandType {

	APPROVE_PAYMENT,
	/** 결제 승인 후 재고 확정이 불가능할 때만 오는 보상 커맨드. 처리는 아직 미구현. */
	CANCEL_PAYMENT;

	private static final Map<String, CommandType> BY_WIRE_VALUE = Stream.of(values())
			.collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

	public static Optional<CommandType> fromWire(String wireValue) {
		return Optional.ofNullable(wireValue).map(BY_WIRE_VALUE::get);
	}
}
