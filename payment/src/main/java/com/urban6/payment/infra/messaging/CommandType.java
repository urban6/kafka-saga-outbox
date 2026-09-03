package com.urban6.payment.infra.messaging;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * payment 가 받아들이는 커맨드. 값이 하나뿐인 건 order 가 보내는 원격 단계가 결제 청구
 * 하나이기 때문이다 — 보상 커맨드는 없다(pivot 이후는 재시도로 민다).
 *
 * 그래도 enum 과 fromWire 를 두는 이유는 모르는 값을 만났을 때 다.
 * valueOf 였다면 order 가 커맨드를 하나 추가하는 순간 그 메시지에서 예외가 나고,
 * 재시도해도 결과가 같아 에러 핸들러가 포기할 때까지(2초 간격 5회) 같은 파티션의 뒷 메시지가 밀린다.
 */
public enum CommandType {

	APPROVE_PAYMENT;

	private static final Map<String, CommandType> BY_WIRE_VALUE = Stream.of(values())
			.collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

	public static Optional<CommandType> fromWire(String wireValue) {
		return Optional.ofNullable(wireValue).map(BY_WIRE_VALUE::get);
	}
}
