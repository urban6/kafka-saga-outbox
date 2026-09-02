package com.urban6.inventory.infra.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 발행 봉투. 이 서비스가 내보내는 모든 메시지의 겉면이다.
 * <p>
 * {@link InboundEnvelope} 와 필드는 같지만 별도 record 인 이유는 payload 의 타입이 반대이기 때문이다.
 * 수신은 {@code JsonNode}(아직 안 읽은 상태), 발행은 {@code Object}(직렬화할 record).
 *
 * @param eventId 컨슈머의 멱등 키가 되는 값. outbox 의 PK 와 반드시 같아야 한다.
 */
public record OutboundEnvelope(
		UUID eventId,
		String eventType,
		int eventVersion,
		String aggregateId,
		String partitionKey,
		Instant occurredAt,
		Object payload,
		Map<String, String> headers
) {

	private static final int DEFAULT_VERSION = 1;

	/**
	 * 받은 메시지에 대한 회신을 만든다.
	 * <p>
	 * {@code aggregateId}(=orderNo)와 {@code partitionKey}, 추적 헤더를 그대로 이어받는 게 핵심이다.
	 * 파티션 키가 바뀌면 같은 주문의 메시지가 다른 파티션으로 흩어져 순서 보장이 깨진다.
	 */
	public static OutboundEnvelope replyTo(InboundEnvelope received, String eventType, Object payload) {
		return new OutboundEnvelope(
				UUID.randomUUID(),
				eventType,
				DEFAULT_VERSION,
				received.aggregateId(),
				received.partitionKey(),
				Instant.now(),
				payload,
				received.headers()
		);
	}
}
