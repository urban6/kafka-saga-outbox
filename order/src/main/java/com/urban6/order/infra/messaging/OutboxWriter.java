package com.urban6.order.infra.messaging;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 비즈니스 로직과 같은 트랜잭션에서 호출한다. 직렬화 실패는 삼키지 않고 던져 롤백시킨다. */
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
