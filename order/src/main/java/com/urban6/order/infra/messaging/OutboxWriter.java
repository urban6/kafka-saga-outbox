package com.urban6.order.infra.messaging;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * EventEnvelope 를 직렬화해 outbox 에 INSERT 한다.
 * 반드시 비즈니스 로직과 같은 트랜잭션에서 호출한다 —
 * 그래야 "주문은 저장됐는데 메시지는 안 나갔다" 가 생기지 않는다.
 */
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public void append(String aggregateType, EventEnvelope<?> envelope) {
        String payload = objectMapper.writeValueAsString(envelope);
        outboxRepository.save(OutboxMessage.of(
                envelope.eventId(),
                aggregateType,
                envelope.aggregateId(),
                envelope.eventType(),
                payload
        ));
    }
}
