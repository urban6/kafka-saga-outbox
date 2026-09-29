package com.urban6.order.application;

import com.urban6.order.config.RetentionProperties;
import com.urban6.order.infra.messaging.IdempotencyGuard;
import com.urban6.order.infra.messaging.OutboxRepository;
import com.urban6.order.infra.persistence.ApiIdempotencyStore;
import com.urban6.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 네이티브 SQL 이라 컴파일러가 못 봐주고, 스케줄러는 며칠 뒤에야 처음 돈다. */
class RetentionCleanupServiceIntegrationTest extends OrderIntegrationTest {

	private static final int NO_BATCH_LIMIT = 100;

	@Autowired
	private OutboxRepository outboxRepository;

	@Autowired
	private IdempotencyGuard idempotencyGuard;

	@Autowired
	private ApiIdempotencyStore apiIdempotencyStore;

	@Test
	@DisplayName("outbox: 임계값 이전 행만 지운다")
	void purgesOutboxRowsOlderThanThreshold() {
		seedOutbox(3, Duration.ofHours(2));
		seedOutbox(2, Duration.ZERO);

		int deleted = outboxRepository.deleteByCreatedAtBefore(ago(Duration.ofHours(1)), NO_BATCH_LIMIT);

		assertThat(deleted).isEqualTo(3);
		assertThat(countOf("outbox")).isEqualTo(2);
	}

	@Test
	@DisplayName("consumed_message: 임계값 이전 행만 지운다")
	void purgesConsumedMessageRowsOlderThanThreshold() {
		seedConsumedMessage(3, Duration.ofHours(2));
		seedConsumedMessage(2, Duration.ZERO);

		int deleted = idempotencyGuard.purgeProcessedBefore(ago(Duration.ofHours(1)), NO_BATCH_LIMIT);

		assertThat(deleted).isEqualTo(3);
		assertThat(countOf("consumed_message")).isEqualTo(2);
	}

	@Test
	@DisplayName("api_idempotency: 임계값 이전 행만 지운다")
	void purgesApiIdempotencyRowsOlderThanThreshold() {
		seedApiIdempotency(3, Duration.ofHours(2));
		seedApiIdempotency(2, Duration.ZERO);

		int deleted = apiIdempotencyStore.purgeCreatedBefore(ago(Duration.ofHours(1)), NO_BATCH_LIMIT);

		assertThat(deleted).isEqualTo(3);
		assertThat(countOf("api_idempotency")).isEqualTo(2);
	}

	@Test
	@DisplayName("한 번의 DELETE 는 배치 상한까지만 지운다")
	void honoursBatchLimit() {
		seedApiIdempotency(5, Duration.ofHours(2));

		int deleted = apiIdempotencyStore.purgeCreatedBefore(ago(Duration.ofHours(1)), 2);

		assertThat(deleted).isEqualTo(2);
		assertThat(countOf("api_idempotency")).isEqualTo(3);
	}

	/** 2 x 2 상한이라 10행 중 4행만 지워진다. */
	@Test
	@DisplayName("회차당 반복 상한을 넘겨 지우지 않는다")
	void stopsAfterMaxBatchesPerRun() {
		seedApiIdempotency(10, Duration.ofHours(2));

		cleanupWith(retention(Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1), 2, 2)).purge();

		assertThat(countOf("api_idempotency")).isEqualTo(6);
	}

	/** consumed_message 가 outbox 임계값으로 지워지면 Kafka retention 보다 짧아져 이중 청구가 난다. */
	@Test
	@DisplayName("테이블마다 자기 보관 주기로 판정한다")
	void usesEachTablesOwnThreshold() {
		seedOutbox(1, Duration.ofMinutes(90));			 // 임계값 1시간 -> 지워진다
		seedConsumedMessage(1, Duration.ofMinutes(90));	 // 임계값 3시간 -> 남는다
		seedApiIdempotency(1, Duration.ofMinutes(30));	 // 임계값 1시간 -> 남는다

		cleanupWith(retention(
				Duration.ofHours(1),
				Duration.ofHours(3),
				Duration.ofHours(1),
				NO_BATCH_LIMIT, 5)).purge();

		assertThat(countOf("outbox")).isZero();
		assertThat(countOf("consumed_message")).isEqualTo(1);
		assertThat(countOf("api_idempotency")).isEqualTo(1);
	}

	/** @TestPropertySource 를 쓰면 컨텍스트가 하나 더 캐시된다. 직접 만든다. */
	private RetentionCleanupService cleanupWith(RetentionProperties properties) {
		return new RetentionCleanupService(outboxRepository, idempotencyGuard, apiIdempotencyStore, properties);
	}

	private RetentionProperties retention(Duration outbox, Duration consumedMessage, Duration apiIdempotency,
			int batchSize, int maxBatchesPerRun) {
		return new RetentionProperties(Duration.ofHours(1),
				outbox, consumedMessage, apiIdempotency, batchSize, maxBatchesPerRun);
	}

	// 시드 시각은 테이블마다 쓰는 쪽에 맞춘다: outbox 는 Hibernate(UTC 벽시계), 나머지는 JdbcTemplate(JVM 시간대).
	// 한 방식으로 통일하면 쿼리가 멀쩡해도 0건이 지워진다.

	private void seedOutbox(int count, Duration age) {
		Timestamp at = utcWallClock(age);
		for (int i = 0; i < count; i++) {
			jdbcTemplate.update(
					"insert into outbox (id, aggregate_type, aggregate_id, event_type, topic, payload, created_at)"
							+ " values (?, 'Order', ?, 'APPROVE_PAYMENT', 'payment.commands', '{}', ?)",
					UUID.randomUUID().toString(), "ORD-RETENTION-" + i, at);
		}
	}

	private void seedConsumedMessage(int count, Duration age) {
		Timestamp at = Timestamp.from(ago(age));
		for (int i = 0; i < count; i++) {
			jdbcTemplate.update(
					"insert into consumed_message (message_id, consumer_group, event_type, processed_at)"
							+ " values (?, 'order-service', 'PAYMENT_APPROVED', ?)",
					UUID.randomUUID().toString(), at);
		}
	}

	private void seedApiIdempotency(int count, Duration age) {
		Timestamp at = Timestamp.from(ago(age));
		String requestHash = "0".repeat(64); // CHAR(64)
		for (int i = 0; i < count; i++) {
			jdbcTemplate.update(
					"insert into api_idempotency (idempotency_key, request_hash, order_no, created_at)"
							+ " values (?, ?, ?, ?)",
					UUID.randomUUID().toString(), requestHash, "ORD-RETENTION-" + i, at);
		}
	}

	private static Instant ago(Duration age) {
		return Instant.now().minus(age);
	}

	private static Timestamp utcWallClock(Duration age) {
		return Timestamp.valueOf(LocalDateTime.ofInstant(ago(age), ZoneOffset.UTC));
	}
}
