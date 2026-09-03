package com.urban6.payment.infra.messaging;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * payment 가 받아들이는 커맨드. 값이 하나뿐인 건 원격 단계가 결제 청구 하나여서다.
 * 그래도 fromWire 를 두는 건 모르는 값 때문이다 — valueOf 였다면 order 가 커맨드를
 * 추가하는 순간 그 메시지가 재시도를 소진할 때까지 같은 파티션의 뒷 메시지가 밀린다.
 */
public enum CommandType {

	APPROVE_PAYMENT;

	private static final Map<String, CommandType> BY_WIRE_VALUE = Stream.of(values())
			.collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

	public static Optional<CommandType> fromWire(String wireValue) {
		return Optional.ofNullable(wireValue).map(BY_WIRE_VALUE::get);
	}
}
