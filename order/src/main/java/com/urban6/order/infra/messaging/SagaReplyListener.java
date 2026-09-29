package com.urban6.order.infra.messaging;

import com.urban6.order.application.OrderSagaOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SagaReplyListener {

	/** 여기 선언해야 config 와 messaging 사이에 순환이 안 생긴다. */
	public static final String CONTAINER_FACTORY = "sagaReplyListenerContainerFactory";

	private final OrderSagaOrchestrator orchestrator;

	@KafkaListener(
			topics = Topics.ORDER_SAGA_REPLIES,
			containerFactory = CONTAINER_FACTORY)
	public void onReply(InboundEnvelope envelope) {
		EventType.fromWire(envelope.eventType())
				.filter(EventType::isSagaReply)
				.ifPresentOrElse(
						eventType -> {
							log.info("saga reply received. eventType={} orderNo={} eventId={}",
									eventType, envelope.aggregateId(), envelope.eventId());
							orchestrator.handleReply(envelope, eventType);
						},
						// 모르는 타입에 예외를 던지면 처리할 수도 없는 메시지를 재시도만 반복한다.
						() -> log.debug("unhandled eventType={} orderNo={}",
								envelope.eventType(), envelope.aggregateId()));
	}
}
