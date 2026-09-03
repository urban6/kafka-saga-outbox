package com.urban6.payment.infra.messaging;

import com.urban6.payment.application.ApprovePaymentService;
import com.urban6.payment.config.KafkaConsumerConfig;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class PaymentCommandListener {

	private static final Logger log = LoggerFactory.getLogger(PaymentCommandListener.class);

	private final ApprovePaymentService approvePaymentService;
	private final JsonMapper jsonMapper;

	@KafkaListener(
			topics = Topics.PAYMENT_COMMANDS,
			containerFactory = KafkaConsumerConfig.CONTAINER_FACTORY)
	public void onCommand(InboundEnvelope envelope) {
		CommandType.fromWire(envelope.eventType()).ifPresentOrElse(
				commandType -> handle(commandType, envelope),
				// 모르는 타입에 예외를 던지면 처리할 수도 없는 메시지를 재시도만 반복한다.
				() -> log.debug("unhandled eventType={} orderNo={}", envelope.eventType(), envelope.aggregateId()));
	}

	private void handle(CommandType commandType, InboundEnvelope envelope) {
		if (commandType != CommandType.APPROVE_PAYMENT) {
			// 지금은 도달하지 않는다(커맨드가 하나뿐). 남겨두는 건 이게 <b>불변조건</b>이라서다 —
			// 커맨드가 하나 늘었을 때 이 분기가 없으면 그 payload 를 승인 요청으로 읽어버린다.
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
