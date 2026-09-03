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

/**
 * 정리 배치는 틀려도 조용하다. 스케줄러가 며칠 뒤에 처음 돌고, 그때 쿼리가 깨졌으면
 * ERROR 로그 한 줄이 남을 뿐이다. 그래서 여기서 미리 실행해 본다.
 *
 * 세 쿼리 모두 네이티브 SQL 문자열이라 컴파일러가 컬럼명도 문법도 봐주지 않는다.
 * 특히 api_idempotency 의 DELETE 는 이 테스트가 생기기 전까지 한 번도 DB 에 닿은 적이 없었다.
 *
 * 가장 무서운 건 문법 오류가 아니라 임계값이 뒤바뀌는 것이다. consumed_message 가
 * 14일이 아니라 7일 만에 지워지면 보관 기간이 Kafka retention 보다 짧아지고,
 * 재전달된 메시지를 처음 보는 것으로 착각해 중복 처리한다 — 결제라면 이중 청구다.
 * 그 변이는 컴파일도 통과하고 로그도 조용하다.
 */
class RetentionCleanupServiceIntegrationTest extends OrderIntegrationTest {

	/** 배치 상한을 검증하지 않는 테스트에서 상한이 결과에 끼어들지 않게 하는 값. */
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

	/** 이 쿼리는 작성된 뒤 한 번도 실행된 적이 없었다. 이 테스트가 첫 실행이다. */
	@Test
	@DisplayName("api_idempotency: 임계값 이전 행만 지운다")
	void purgesApiIdempotencyRowsOlderThanThreshold() {
		seedApiIdempotency(3, Duration.ofHours(2));
		seedApiIdempotency(2, Duration.ZERO);

		int deleted = apiIdempotencyStore.purgeCreatedBefore(ago(Duration.ofHours(1)), NO_BATCH_LIMIT);

		assertThat(deleted).isEqualTo(3);
		assertThat(countOf("api_idempotency")).isEqualTo(2);
	}

	/**
	 * limit 이 실제로 먹는지 본다. JPQL 로 표현할 수 없어서 네이티브 쿼리를 쓴 바로 그 부분이다.
	 * 상한이 없으면 정리 배치가 한 번에 수백만 행을 잠가 스스로 장애가 된다.
	 */
	@Test
	@DisplayName("한 번의 DELETE 는 배치 상한까지만 지운다")
	void honoursBatchLimit() {
		seedApiIdempotency(5, Duration.ofHours(2));

		int deleted = apiIdempotencyStore.purgeCreatedBefore(ago(Duration.ofHours(1)), 2);

		assertThat(deleted).isEqualTo(2);
		assertThat(countOf("api_idempotency")).isEqualTo(3);
	}

	/**
	 * 밀린 양이 많아도 한 회차가 DB 를 독점하지 않는다. 남은 건 다음 회차가 가져간다.
	 *
	 * 상한이 2 x 2 이므로 10행 중 4행만 지워져야 한다. 반복 상한이 사라지면 10행이 전부 지워진다.
	 */
	@Test
	@DisplayName("회차당 반복 상한을 넘겨 지우지 않는다")
	void stopsAfterMaxBatchesPerRun() {
		seedApiIdempotency(10, Duration.ofHours(2));

		cleanupWith(retention(Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1), 2, 2)).purge();

		assertThat(countOf("api_idempotency")).isEqualTo(6);
	}

	/**
	 * 세 테이블이 각자 자기 임계값을 쓴다.
	 *
	 * 이 클래스에서 가장 중요한 테스트다. outbox 와 consumedMessage 가 뒤바뀌면
	 * 90분 된 두 행의 운명이 동시에 뒤집혀 잡힌다.
	 */
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

	/**
	 * 스프링 컨텍스트를 새로 띄우지 않는다. RetentionProperties 가 record 고 서비스가
	 * 생성자 주입이라 직접 만들면 된다. @TestPropertySource 로 오버라이드하면
	 * 컨텍스트가 하나 더 캐시돼 통합 테스트가 그만큼 느려진다.
	 */
	private RetentionCleanupService cleanupWith(RetentionProperties properties) {
		return new RetentionCleanupService(outboxRepository, idempotencyGuard, apiIdempotencyStore, properties);
	}

	private RetentionProperties retention(Duration outbox, Duration consumedMessage, Duration apiIdempotency,
			int batchSize, int maxBatchesPerRun) {
		// scanInterval 은 @Scheduled 가 문자열로 읽으므로 여기서는 아무 값이나 된다.
		return new RetentionProperties(Duration.ofHours(1),
				outbox, consumedMessage, apiIdempotency, batchSize, maxBatchesPerRun);
	}

	// 시드는 JDBC 로 직접 넣는다. 엔티티를 거치면 @PrePersist 가 현재 시각으로 덮어써서
	// 과거 행을 만들 수 없다. 시간을 조작하는 대신 과거를 심는 편이 빠르고 정확하다.
	//
	// 다만 테이블마다 시각을 저장하는 벽시계가 다르므로 시드도 그에 맞춰야 한다.
	// outbox 는 Hibernate 가 쓰고 지우는데 hibernate.jdbc.time_zone 이 UTC 라 UTC 벽시계로 남고,
	// consumed_message 와 api_idempotency 는 JdbcTemplate 이 쓰고 지우므로 JVM 기본 시간대다.
	// 각자 안에서는 일관되지만 서로 다르다 — 여기서 한 방식으로 통일해 심으면
	// 쿼리가 멀쩡한데도 아무것도 안 지워진다(실제로 이걸로 한 번 실패했다).

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
		Timestamp at = Timestamp.from(ago(age));	// JdbcTemplate 이 쓰는 방식 그대로
		for (int i = 0; i < count; i++) {
			jdbcTemplate.update(
					"insert into consumed_message (message_id, consumer_group, event_type, processed_at)"
							+ " values (?, 'order-service', 'PAYMENT_APPROVED', ?)",
					UUID.randomUUID().toString(), at);
		}
	}

	private void seedApiIdempotency(int count, Duration age) {
		Timestamp at = Timestamp.from(ago(age));	// JdbcTemplate 이 쓰는 방식 그대로
		String requestHash = "0".repeat(64); // CHAR(64) 라 길이를 맞춘다
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

	/** Hibernate 가 hibernate.jdbc.time_zone=UTC 로 남기는 것과 같은 벽시계 값. */
	private static Timestamp utcWallClock(Duration age) {
		return Timestamp.valueOf(LocalDateTime.ofInstant(ago(age), ZoneOffset.UTC));
	}
}
