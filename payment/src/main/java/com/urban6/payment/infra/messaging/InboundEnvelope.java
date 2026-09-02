package com.urban6.payment.infra.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * 수신 메시지의 공통 봉투. <b>tolerant reader</b> 다. 발행용 {@link EventEnvelope} 와 일부러 비대칭이다.
 * <ul>
 *   <li>{@code eventType} 이 String — enum 이면 모르는 타입 하나에 파티션이 통째로 막힌다</li>
 *   <li>{@code payload} 가 {@link JsonNode} — 한 토픽에 여러 커맨드가 섞여 오므로
 *       eventType 을 본 뒤에야 어떤 record 로 읽을지 정할 수 있다</li>
 *   <li>제네릭 없음 — {@code JacksonJsonDeserializer} 에 구체 Class 를 넘겨야 한다</li>
 * </ul>
 * 모르는 필드는 Jackson 3 기본값({@code FAIL_ON_UNKNOWN_PROPERTIES=false})이 알아서 무시한다.
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
