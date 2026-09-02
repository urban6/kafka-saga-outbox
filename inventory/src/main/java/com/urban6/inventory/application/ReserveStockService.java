package com.urban6.inventory.application;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.urban6.inventory.domain.StockReservation;
import com.urban6.inventory.infra.messaging.EventTypes;
import com.urban6.inventory.infra.messaging.IdempotencyGuard;
import com.urban6.inventory.infra.messaging.InboundEnvelope;
import com.urban6.inventory.infra.messaging.OutboundEnvelope;
import com.urban6.inventory.infra.messaging.OutboxWriter;
import com.urban6.inventory.infra.messaging.ReserveStockCommand;
import com.urban6.inventory.infra.messaging.StockReplyPayload;
import com.urban6.inventory.infra.messaging.Topics;
import com.urban6.inventory.infra.persistence.ProductRepository;
import com.urban6.inventory.infra.persistence.StockReservationRepository;

/**
 * 사가 1단계: 재고 예약.
 * <p>
 * 멱등 선점 → 재고 차감 → 예약 저장 → 회신 적재가 <b>하나의 트랜잭션</b>이다. 중간에 어디서
 * 실패하든 전부 없던 일이 되고, 커밋되면 넷이 함께 binlog 에 실린다.
 */
@Service
public class ReserveStockService {

	private static final Logger log = LoggerFactory.getLogger(ReserveStockService.class);

	private static final String AGGREGATE_TYPE = "StockReservation";

	private final ProductRepository productRepository;
	private final StockReservationRepository reservationRepository;
	private final IdempotencyGuard idempotencyGuard;
	private final OutboxWriter outboxWriter;
	private final String consumerGroup;

	public ReserveStockService(ProductRepository productRepository,
			StockReservationRepository reservationRepository,
			IdempotencyGuard idempotencyGuard,
			OutboxWriter outboxWriter,
			@Value("${spring.kafka.consumer.group-id}") String consumerGroup) {
		this.productRepository = productRepository;
		this.reservationRepository = reservationRepository;
		this.idempotencyGuard = idempotencyGuard;
		this.outboxWriter = outboxWriter;
		this.consumerGroup = consumerGroup;
	}

	@Transactional
	public void handle(InboundEnvelope envelope, ReserveStockCommand command) {
		// 1차 방어선. 이미 처리한 메시지면 재고를 두 번 깎지 않고 조용히 끝낸다.
		if (!idempotencyGuard.claim(envelope.eventId(), consumerGroup, envelope.eventType())) {
			log.info("duplicate message ignored. eventId={} orderNo={}",
					envelope.eventId(), command.orderNo());
			return;
		}

		List<ReserveStockCommand.Line> applied = new ArrayList<>();
		for (ReserveStockCommand.Line line : command.lines()) {
			// 0건이면 가용 재고 부족. 조회 후 검증이 아니라 UPDATE 의 원자성으로 판정한다.
			if (productRepository.reserve(line.productId(), line.quantity()) == 0) {
				reject(envelope, command, applied, line);
				return;
			}
			applied.add(line);
		}

		List<StockReservation> reserved = applied.stream()
				.map(line -> StockReservation.reserve(command.orderNo(), line.productId(), line.quantity()))
				.toList();
		reservationRepository.saveAll(reserved);

		List<String> reservationIds = reserved.stream()
				.map(StockReservation::getReservationId)
				.toList();

		outboxWriter.append(AGGREGATE_TYPE, Topics.ORDER_SAGA_REPLIES,
				OutboundEnvelope.replyTo(envelope, EventTypes.STOCK_RESERVED,
						StockReplyPayload.reserved(command.orderNo(), reservationIds)));

		log.info("stock reserved. orderNo={} lines={}", command.orderNo(), reserved.size());
	}

	/**
	 * 한 라인이라도 재고가 모자라면 주문 전체를 거부한다.
	 * <p>
	 * 앞선 라인에서 이미 늘려둔 예약 수량을 여기서 되돌린다. 예외를 던져 트랜잭션을 롤백시키면
	 * 안 되는데, 그러면 회신 outbox 행까지 함께 사라져 order 가 결과를 영영 못 받기 때문이다.
	 * <b>거부는 장애가 아니라 정상 흐름</b>이다.
	 */
	private void reject(InboundEnvelope envelope, ReserveStockCommand command,
			List<ReserveStockCommand.Line> applied, ReserveStockCommand.Line failed) {

		applied.forEach(line -> productRepository.undoReserve(line.productId(), line.quantity()));

		String reason = "insufficient stock. productId=" + failed.productId()
				+ " quantity=" + failed.quantity();

		outboxWriter.append(AGGREGATE_TYPE, Topics.ORDER_SAGA_REPLIES,
				OutboundEnvelope.replyTo(envelope, EventTypes.STOCK_REJECTED,
						StockReplyPayload.rejected(command.orderNo(), reason)));

		log.info("stock rejected. orderNo={} {}", command.orderNo(), reason);
	}
}
