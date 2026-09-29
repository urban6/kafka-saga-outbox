package com.urban6.order.application;

import com.urban6.order.config.RetentionProperties;
import com.urban6.order.infra.messaging.IdempotencyGuard;
import com.urban6.order.infra.messaging.OutboxRepository;
import com.urban6.order.infra.persistence.ApiIdempotencyStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.function.IntUnaryOperator;

/** @Transactional 이 일부러 없다. 호출마다 트랜잭션이 끊겨야 락이 실제로 풀린다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetentionCleanupService {

	private final OutboxRepository outboxRepository;
	private final IdempotencyGuard idempotencyGuard;
	private final ApiIdempotencyStore apiIdempotencyStore;
	private final RetentionProperties properties;

	@Scheduled(fixedDelayString = "${retention.scan-interval}")
	public void purge() {
		Instant now = Instant.now();

		int outbox = purgeInBatches(
				batch -> outboxRepository.deleteByCreatedAtBefore(now.minus(properties.outbox()), batch));
		int consumed = purgeInBatches(
				batch -> idempotencyGuard.purgeProcessedBefore(now.minus(properties.consumedMessage()), batch));
		int api = purgeInBatches(
				batch -> apiIdempotencyStore.purgeCreatedBefore(now.minus(properties.apiIdempotency()), batch));

		if (outbox + consumed + api == 0) {
			log.debug("retention cleanup: nothing to purge");
			return;
		}
		log.info("retention cleanup done. outbox={} consumedMessage={} apiIdempotency={}",
				outbox, consumed, api);
	}

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
