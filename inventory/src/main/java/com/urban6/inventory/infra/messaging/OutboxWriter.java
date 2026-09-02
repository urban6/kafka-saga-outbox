package com.urban6.inventory.infra.messaging;

import tools.jackson.databind.ObjectMapper;

/**
 * {@link OutboundEnvelope} 를 직렬화해 outbox 에 INSERT 한다.
 * <p>
 * 반드시 비즈니스 로직과 <b>같은 트랜잭션</b> 안에서 호출한다. 직렬화 실패는 삼키지 않고 그대로
 * 던진다 — 여기서 예외를 먹으면 재고는 줄었는데 회신은 없는 상태가 되어 사가가 영원히 멈춘다.
 * Jackson 3 의 예외는 unchecked 라 별도 처리 없이 트랜잭션이 롤백된다.
 */
public class OutboxWriter {

	private final OutboxRepository outboxRepository;
	private final ObjectMapper objectMapper;

	public OutboxWriter(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
		this.outboxRepository = outboxRepository;
		this.objectMapper = objectMapper;
	}

	/**
	 * @param aggregateType 애그리거트 종류. 이 서비스에서는 "StockReservation"
	 * @param topic         발행될 토픽. {@link Topics} 의 상수
	 */
	public void append(String aggregateType, String topic, OutboundEnvelope envelope) {
		String payload = objectMapper.writeValueAsString(envelope);
		outboxRepository.save(OutboxMessage.of(
				envelope.eventId(),
				aggregateType,
				envelope.aggregateId(),
				envelope.eventType(),
				topic,
				payload
		));
	}
}
