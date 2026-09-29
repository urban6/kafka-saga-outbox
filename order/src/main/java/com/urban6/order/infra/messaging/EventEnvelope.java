package com.urban6.order.infra.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 발행 전용. eventId 는 재발행해도 같아야 컨슈머 멱등이 동작한다. */
public record EventEnvelope<T>(
		UUID eventId,
		EventType eventType,
		int eventVersion,
		String aggregateId,
		String partitionKey,
		Instant occurredAt,
		T payload,
		Map<String, String> headers
) {

	private static final int DEFAULT_VERSION = 1;

	// headers 를 불변 복사한다. 발행 뒤 원본 맵이 바뀌면 outbox 에 남은 JSON 과 값이 갈린다.
	public EventEnvelope {
		headers = headers == null ? Map.of() : Map.copyOf(headers);
	}

	public static <T> EventEnvelope<T> of(EventType eventType, String aggregateId, T payload) {
		return new EventEnvelope<>(
				UUID.randomUUID(),
				eventType,
				DEFAULT_VERSION,
				aggregateId,
				aggregateId,
				Instant.now(),
				payload,
				Map.of()
		);
	}
}
