package com.urban6.payment.infra.messaging;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 반드시 비즈니스 로직과 같은 트랜잭션에서 호출한다. */
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
