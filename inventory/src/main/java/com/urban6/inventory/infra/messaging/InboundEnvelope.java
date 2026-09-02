package com.urban6.inventory.infra.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * 수신 메시지의 공통 봉투. <b>tolerant reader</b> 로 만든다.
 * <p>
 * 프로듀서(order)의 {@code EventEnvelope} 와 다른 점이 셋 있고, 전부 의도된 것이다.
 * <ul>
 *   <li>{@code eventType} 이 String — enum 이면 모르는 타입에서 파티션이 막힌다</li>
 *   <li>{@code payload} 가 {@link JsonNode} — 한 토픽에 RESERVE/CONFIRM/RELEASE 가 섞여
 *       들어오므로 eventType 을 본 뒤에야 어떤 record 로 읽을지 정할 수 있다</li>
 *   <li>제네릭 없음 — {@code JacksonJsonDeserializer} 에 구체 Class 를 넘겨야 한다</li>
 * </ul>
 * 모르는 필드는 무시한다({@code FAIL_ON_UNKNOWN_PROPERTIES=false}, KafkaConsumerConfig 참조).
 * 그래야 프로듀서가 봉투에 필드를 추가해도 이쪽이 그대로 돈다.
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
