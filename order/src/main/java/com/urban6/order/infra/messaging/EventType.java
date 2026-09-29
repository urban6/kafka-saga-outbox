package com.urban6.order.infra.messaging;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** name() 이 outbox·카프카에 실리는 와이어 값이라 함부로 바꾸지 않는다. */
public enum EventType {

	APPROVE_PAYMENT(Topics.PAYMENT_COMMANDS),

	PAYMENT_APPROVED(Topics.ORDER_SAGA_REPLIES),
	PAYMENT_REJECTED(Topics.ORDER_SAGA_REPLIES),

	ORDER_COMPLETED(Topics.ORDER_EVENTS),
	ORDER_CANCELED(Topics.ORDER_EVENTS);

	private final String topic;

	EventType(String topic) {
		this.topic = topic;
	}

	public String topic() {
		return topic;
	}

	private static final Map<String, EventType> BY_WIRE_VALUE = Stream.of(values())
			.collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

	/** 모르는 값이면 예외 대신 빈 Optional. 변환은 여기 한 곳에만 둔다. */
	public static Optional<EventType> fromWire(String wireValue) {
		return Optional.ofNullable(wireValue).map(BY_WIRE_VALUE::get);
	}

	/** 자기가 발행하는 커맨드가 회신 토픽에 섞여 와도 걸러낸다. */
	public boolean isSagaReply() {
		return Topics.ORDER_SAGA_REPLIES.equals(topic);
	}
}
