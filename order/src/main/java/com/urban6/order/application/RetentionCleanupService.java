package com.urban6.order.application;

import com.urban6.order.config.RetentionProperties;
import com.urban6.order.infra.messaging.IdempotencyGuard;
import com.urban6.order.infra.messaging.OutboxRepository;
import com.urban6.order.infra.persistence.ApiIdempotencyStore;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.function.IntUnaryOperator;

/**
 * 보관 주기가 지난 운영 테이블을 지운다. outbox · consumed_message · api_idempotency 셋이 대상이다.
 *
 * @Transactional 이 없다. 일부러 없다 — 삭제를 한 트랜잭션으로 묶으면 배치 상한이 의미를 잃는다.
 * 호출마다 트랜잭션이 끊겨야 락이 실제로 풀리고, 다른 요청이 그 틈에 들어온다.
 *
 * 실패해도 다음 회차가 같은 일을 한다. 그래서 예외를 특별히 다루지 않는다 —
 * 정리 배치는 못 지우는 것보다 남의 트랜잭션을 막는 것이 훨씬 나쁘다.
 */
@Service
@RequiredArgsConstructor
public class RetentionCleanupService {

	private static final Logger log = LoggerFactory.getLogger(RetentionCleanupService.class);

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

	/**
	 * 상한만큼 지우기를 반복하다가, 지운 수가 상한에 못 미치면 더 지울 게 없다는 뜻이라 멈춘다.
	 *
	 * 반복 자체에도 상한을 둔다. 밀린 양이 많으면 다음 회차로 넘긴다 —
	 * 한 번에 따라잡으려다 DB 를 독점하는 것보다 며칠에 걸쳐 줄어드는 편이 안전하다.
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
