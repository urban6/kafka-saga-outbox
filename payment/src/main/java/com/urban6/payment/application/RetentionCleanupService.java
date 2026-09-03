package com.urban6.payment.application;

import com.urban6.payment.config.RetentionProperties;
import com.urban6.payment.infra.messaging.IdempotencyGuard;
import com.urban6.payment.infra.messaging.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.function.IntUnaryOperator;

/**
 * 보관 주기가 지난 outbox 와 consumed_message 를 지운다.
 * payment 테이블은 안 지운다 — 운영 데이터가 아니라 기록이다.
 *
 * @Transactional 이 일부러 없다. 한 트랜잭션으로 묶으면 배치 상한이 의미를 잃는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetentionCleanupService {

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
