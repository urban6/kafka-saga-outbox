package com.urban6.payment.infra.messaging;

import com.urban6.payment.application.ApprovePaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCommandListener {

	/** 여기 선언해야 config 와 messaging 이 순환하지 않는다. */
	public static final String CONTAINER_FACTORY = "paymentCommandListenerContainerFactory";

	private final ApprovePaymentService approvePaymentService;
	private final JsonMapper jsonMapper;

	@KafkaListener(
			topics = Topics.PAYMENT_COMMANDS,
			containerFactory = CONTAINER_FACTORY)
	public void onCommand(InboundEnvelope envelope) {
		CommandType.fromWire(envelope.eventType()).ifPresentOrElse(
				commandType -> handle(commandType, envelope),
				() -> log.debug("unhandled eventType={} orderNo={}", envelope.eventType(), envelope.aggregateId()));
	}

	private void handle(CommandType commandType, InboundEnvelope envelope) {
		if (commandType != CommandType.APPROVE_PAYMENT) {
			// 도달하지 않지만 불변조건이다. 없으면 새 커맨드의 payload 를 승인 요청으로 읽어버린다.
			log.info("command not supported. commandType={} orderNo={}",
					commandType, envelope.aggregateId());
			return;
		}

		ApprovePaymentCommand command =
				jsonMapper.treeToValue(envelope.payload(), ApprovePaymentCommand.class);

		log.info("approve payment command received. orderNo={} customerId={} amount={} eventId={}",
				command.orderNo(), command.customerId(), command.amount(), envelope.eventId());

		approvePaymentService.approve(
				envelope.eventId(), command.orderNo(), command.customerId(), command.amount());
	}
}
