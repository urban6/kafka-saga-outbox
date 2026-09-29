package com.urban6.payment.infra.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** @param eventId 재발행해도 같은 값을 유지해야 컨슈머 멱등이 동작한다 */
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

	// 불변 복사하지 않으면 발행 뒤 원본 맵이 바뀔 때 outbox 의 JSON 과 값이 갈린다.
	public EventEnvelope {
		headers = headers == null ? Map.of() : Map.copyOf(headers);
	}

	/** aggregateId 는 항상 orderNo 다 — 회신이 커맨드와 같은 파티션으로 가야 한다. */
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
