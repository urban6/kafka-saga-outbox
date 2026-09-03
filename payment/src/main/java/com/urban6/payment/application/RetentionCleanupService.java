package com.urban6.payment.application;

import com.urban6.payment.config.RetentionProperties;
import com.urban6.payment.infra.messaging.IdempotencyGuard;
import com.urban6.payment.infra.messaging.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.function.IntUnaryOperator;

/**
 * 보관 주기가 지난 운영 테이블을 지운다. outbox 와 consumed_message 둘이 대상이다.
 * <p>
 * <b>{@code @Transactional} 이 없다.</b> 일부러 없다 — 삭제를 한 트랜잭션으로 묶으면 배치 상한이 의미를 잃는다.
 * 호출마다 트랜잭션이 끊겨야 락이 실제로 풀리고, 다른 요청이 그 틈에 들어온다.
 * <p>
 * {@code payment} 테이블은 지우지 않는다. 결제 이력은 운영 데이터가 아니라 <b>기록</b>이다.
 */
@Service
@RequiredArgsConstructor
public class RetentionCleanupService {

	private static final Logger log = LoggerFactory.getLogger(RetentionCleanupService.class);

	private final OutboxRepository outboxRepository;
	private final IdempotencyGuard idempotencyGuard;
	private final RetentionProperties properties;

	@Scheduled(fixedDelayString = "${retention.scan-interval}")
	public void purge() {
		Instant now = Instant.now();

		int outbox = purgeInBatches(
				batch -> outboxRepository.deleteByCreatedAtBefore(now.minus(properties.outbox()), batch));
		int consumed = purgeInBatches(
				batch -> idempotencyGuard.purgeProcessedBefore(now.minus(properties.consumedMessage()), batch));

		if (outbox + consumed == 0) {
			log.debug("retention cleanup: nothing to purge");
			return;
		}
		log.info("retention cleanup done. outbox={} consumedMessage={}", outbox, consumed);
	}

	/**
	 * 상한만큼 지우기를 반복하다가, 지운 수가 상한에 못 미치면 더 지울 게 없다는 뜻이라 멈춘다.
	 * 반복 자체에도 상한을 둔다 — 한 번에 따라잡으려다 DB 를 독점하지 않게.
	 */
	private int purgeInBatches(IntUnaryOperator deleteBatch) {
		int batchSize = properties.batchSize();
		int total = 0;
		for (int i = 0; i < properties.maxBatchesPerRun(); i++) {
			int deleted = deleteBatch.applyAsInt(batchSize);
			total += deleted;
			if (deleted < batchSize) {
				break;
			}
		}
		return total;
	}
}
