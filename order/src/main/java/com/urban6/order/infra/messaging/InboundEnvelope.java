package com.urban6.order.infra.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * 수신 봉투. 발행용 {@link EventEnvelope} 와 <b>일부러 비대칭</b>이다.
 * <ul>
 *   <li>{@code eventType} 이 String — enum 이면 모르는 타입마다 역직렬화가 실패하고,
 *       그건 재시도로 낫지 않아 <b>파티션이 통째로 막힌다</b>. payment 가 회신 종류를 추가해도
 *       order 는 무시하고 지나가야 한다</li>
 *   <li>{@code payload} 가 {@link JsonNode} — 한 토픽에 승인/거절/취소 회신이 섞여 오므로
 *       eventType 을 본 뒤에야 어떤 record 로 읽을지 정할 수 있다</li>
 *   <li>제네릭 없음 — {@code JacksonJsonDeserializer} 에 구체 Class 를 넘겨야 한다</li>
 * </ul>
 * 모르는 필드는 Jackson 3 기본값({@code FAIL_ON_UNKNOWN_PROPERTIES=false})이 무시한다.
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
