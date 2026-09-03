package com.urban6.payment.infra.messaging;

import com.urban6.payment.application.ApprovePaymentService;
import com.urban6.payment.config.KafkaConsumerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@code payment.commands} 수신 지점.
 * <p>
 * 하는 일은 셋뿐이다 — 커맨드 종류를 해석하고, payload 를 record 로 읽고, 유스케이스에 넘긴다.
 * 결제 판정도 상태 전이도 여기 두지 않는다.
 */
@Component
public class PaymentCommandListener {

	private static final Logger log = LoggerFactory.getLogger(PaymentCommandListener.class);

	private final ApprovePaymentService approvePaymentService;
	private final JsonMapper jsonMapper;

	public PaymentCommandListener(ApprovePaymentService approvePaymentService, JsonMapper jsonMapper) {
		this.approvePaymentService = approvePaymentService;
		this.jsonMapper = jsonMapper;
	}

	@KafkaListener(
			topics = Topics.PAYMENT_COMMANDS,
			containerFactory = KafkaConsumerConfig.CONTAINER_FACTORY)
	public void onCommand(InboundEnvelope envelope) {
		CommandType.fromWire(envelope.eventType()).ifPresentOrElse(
				commandType -> handle(commandType, envelope),
				// 모르는 타입에 예외를 던지면 처리할 수도 없는 메시지를 재시도만 반복한다.
				() -> log.debug("unhandled eventType={} orderNo={}",
						envelope.eventType(), envelope.aggregateId()));
	}

	private void handle(CommandType commandType, InboundEnvelope envelope) {
		if (commandType != CommandType.APPROVE_PAYMENT) {
			// CANCEL_PAYMENT 는 아직 구현하지 않았다. 같은 토픽으로 들어오므로 조용히 넘긴다.
			log.info("command not supported yet. commandType={} orderNo={}",
					commandType, envelope.aggregateId());
			return;
		}

		ApprovePaymentCommand command =
				jsonMapper.treeToValue(envelope.payload(), ApprovePaymentCommand.class);

		log.info("approve payment command received. orderNo={} amount={} paymentKey={} eventId={}",
				command.orderNo(), command.amount(), command.paymentKey(), envelope.eventId());

		approvePaymentService.approve(
				envelope.eventId(), command.orderNo(), command.paymentKey(), command.amount());
	}
}
