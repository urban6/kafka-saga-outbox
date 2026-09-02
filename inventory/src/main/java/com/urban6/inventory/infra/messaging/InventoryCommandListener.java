package com.urban6.inventory.infra.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.urban6.inventory.application.ReserveStockService;
import com.urban6.inventory.config.KafkaConsumerConfig;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@code inventory.commands} 수신 지점.
 * <p>
 * 하는 일은 셋뿐이다 — eventType 을 보고, payload 를 record 로 읽고, 유스케이스에 넘긴다.
 * 상태 전이나 재고 판단은 여기 두지 않는다.
 */
@Component
public class InventoryCommandListener {

	private static final Logger log = LoggerFactory.getLogger(InventoryCommandListener.class);

	private final ReserveStockService reserveStockService;
	private final JsonMapper jsonMapper;

	public InventoryCommandListener(ReserveStockService reserveStockService, JsonMapper jsonMapper) {
		this.reserveStockService = reserveStockService;
		this.jsonMapper = jsonMapper;
	}

	@KafkaListener(
			topics = Topics.INVENTORY_COMMANDS,
			containerFactory = KafkaConsumerConfig.CONTAINER_FACTORY)
	public void onCommand(InboundEnvelope envelope) {
		switch (envelope.eventType()) {
			case EventTypes.RESERVE_STOCK -> reserveStockService.handle(
					envelope, jsonMapper.treeToValue(envelope.payload(), ReserveStockCommand.class));

			// CONFIRM_STOCK / RELEASE_STOCK 은 아직 미구현이고, 같은 토픽으로 들어온다.
			// 여기서 예외를 던지면 처리할 수도 없는 메시지를 재시도만 반복하게 된다.
			default -> log.debug("ignored. eventType={} orderNo={}",
					envelope.eventType(), envelope.aggregateId());
		}
	}
}
