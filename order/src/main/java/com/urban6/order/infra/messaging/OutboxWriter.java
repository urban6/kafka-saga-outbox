package com.urban6.order.infra.messaging;

import tools.jackson.databind.ObjectMapper;

/**
 * EventEnvelope 를 직렬화해 outbox 에 INSERT 한다.
 * <p>
 * 반드시 비즈니스 로직과 <b>같은 트랜잭션</b> 안에서 호출한다. 그래야 "주문은 저장됐는데 메시지는 안 나갔다"가 생기지 않는다.
 * <p>
 * {@code @Component} 를 붙이지 않은 이유는 {@link IdempotencyGuard} 와 같다. 어떤 서비스가 무엇을 쓸지는 라이브러리가 아니라 서비스 모듈의 {@code config} 가
 * 정한다.
 */
public class OutboxWriter {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

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
