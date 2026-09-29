package com.urban6.payment.infra.messaging;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** valueOf 대신 fromWire 를 쓴다 — 모르는 커맨드가 재시도를 소진할 때까지 파티션을 밀지 않게. */
public enum CommandType {

	APPROVE_PAYMENT;

	private static final Map<String, CommandType> BY_WIRE_VALUE = Stream.of(values())
			.collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

	public static Optional<CommandType> fromWire(String wireValue) {
		return Optional.ofNullable(wireValue).map(BY_WIRE_VALUE::get);
	}
}
