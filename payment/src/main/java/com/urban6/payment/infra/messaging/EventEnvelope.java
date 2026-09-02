package com.urban6.payment.infra.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 서비스 사이를 오가는 모든 메시지의 공통 봉투.
 * <p>
 * payload 만 이벤트마다 다르고, 나머지 메타데이터는 라우팅·순서 보장·중복 제거에 쓰인다.
 * eventId 는 컨슈머 쪽 멱등 처리 키({@code consumed_message.message_id})와 같은 값이다.
 *
 * @param eventId      메시지 고유 식별자. 재발행해도 같은 값을 유지해야 중복 제거가 동작한다.
 * @param eventType    메시지 종류
 * @param eventVersion 페이로드 스키마 버전. 필드가 깨지는 변경을 할 때 올린다.
 * @param aggregateId  이 이벤트를 만든 애그리거트 식별자(주문이면 orderNo)
 * @param partitionKey 카프카 파티션 키. 같은 주문의 메시지 순서를 지키려고 보통 orderNo 를 쓴다.
 * @param occurredAt   이벤트가 발생한 시각
 * @param payload      이벤트 본문
 * @param headers      추적용 부가 정보(traceId 등). 없으면 빈 맵.
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

	@JsonCreator
	public EventEnvelope(
			@JsonProperty("eventId") UUID eventId,
			@JsonProperty("eventType") EventType eventType,
			@JsonProperty("eventVersion") int eventVersion,
			@JsonProperty("aggregateId") String aggregateId,
			@JsonProperty("partitionKey") String partitionKey,
			@JsonProperty("occurredAt") Instant occurredAt,
			@JsonProperty("payload") T payload,
			@JsonProperty("headers") Map<String, String> headers
	) {
		this.eventId = eventId;
		this.eventType = eventType;
		this.eventVersion = eventVersion;
		this.aggregateId = aggregateId;
		this.partitionKey = partitionKey;
		this.occurredAt = occurredAt;
		this.payload = payload;
		this.headers = headers == null ? Map.of() : Map.copyOf(headers);
	}

	/** 새 이벤트 생성. {@code aggregateId} 는 항상 {@code orderNo} 다 — 회신이 커맨드와 같은 파티션으로 가야 한다. */
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

	public String topic() {
		return eventType.topic();
	}
}
