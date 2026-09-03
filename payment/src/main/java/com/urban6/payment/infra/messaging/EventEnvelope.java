package com.urban6.payment.infra.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 서비스 사이를 오가는 모든 메시지의 공통 봉투. 발행 전용이며 수신은 InboundEnvelope 가 맡는다.
 *
 * @param eventId 재발행해도 같은 값을 유지해야 컨슈머 멱등이 동작한다
 * @param headers 추적용 부가 정보. 없으면 빈 맵
 */
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

	/** 새 이벤트 생성. aggregateId 는 항상 orderNo 다 — 회신이 커맨드와 같은 파티션으로 가야 한다. */
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
