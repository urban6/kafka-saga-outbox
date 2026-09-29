package com.urban6.payment.infra.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * 발행용 EventEnvelope 와 일부러 비대칭이다. eventType 이 enum 이면 모르는 타입에서 역직렬화가 실패해
 * 파티션이 막히고, payload 는 eventType 을 본 뒤에야 어떤 record 로 읽을지 정한다.
 */
public record InboundEnvelope(
		UUID eventId,
		String eventType,
		int eventVersion,
		String aggregateId,
		String partitionKey,
		Instant occurredAt,
		JsonNode payload,
		Map<String, String> headers
) {

	public InboundEnvelope {
		headers = headers == null ? Map.of() : Map.copyOf(headers);
	}
}
