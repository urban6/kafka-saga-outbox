package com.urban6.order.infra.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 서비스 사이를 오가는 모든 메시지의 공통 봉투.
 *
 * payload 만 이벤트마다 다르고, 나머지 메타데이터는 라우팅·순서 보장·중복 제거에 쓰인다.
 * eventId 는 컨슈머 쪽 멱등 처리 키(consumed_message.message_id)와 같은 값이다.
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

	/**
	 * 이 봉투는 직렬화만 된다(outbox 로 나갈 때). 역직렬화는 InboundEnvelope 몫이라
	 * Jackson 애노테이션이 필요 없다 — record 는 Jackson 3 가 그대로 읽고,
	 * 애초에 이 타입으로 읽는 코드가 없다.
	 *
	 * headers 만 방어한다. 널이면 빈 맵으로, 아니면 불변 복사본으로 — 봉투가 발행된 뒤에
	 * 원본 맵이 바뀌면 outbox 에 남은 JSON 과 메모리의 값이 갈린다.
	 */
	public EventEnvelope {
		headers = headers == null ? Map.of() : Map.copyOf(headers);
	}

	/** 새 이벤트 생성. eventId 는 새로 발급하고 파티션 키는 aggregateId 를 그대로 쓴다. */
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
