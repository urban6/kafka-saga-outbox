package com.urban6.order.infra.messaging;

import java.time.Instant;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 컨슈머 중복 처리 방지. 처리 직전에 (messageId, consumerGroup) 을 선점하고,
 * 이미 있으면 건너뛴다. 선점과 실제 처리는 반드시 같은 트랜잭션이어야 한다 —
 * 분리하면 처리가 롤백돼도 선점만 남아 그 메시지는 영원히 재처리되지 않는다.
 */
@RequiredArgsConstructor
public class IdempotencyGuard {

	// MariaDB/MySQL 전용. 이미 있는 행이면 0을 돌려주고 조용히 넘어간다.
	private static final String CLAIM = """
			insert ignore into consumed_message
			    (message_id, consumer_group, event_type, processed_at)
			values (?, ?, ?, ?)
			""";

	private static final String PURGE = "delete from consumed_message where processed_at < ? limit ?";

	private final JdbcTemplate jdbcTemplate;

	/**
	 * 이 메시지를 처음 처리하는 것이면 true. 이미 처리한 메시지면 false 이므로 호출부는 즉시 ack 하고 끝낸다.
	 */
	public boolean claim(UUID messageId, String consumerGroup, EventType eventType) {
		int inserted = jdbcTemplate.update(CLAIM,
				messageId.toString(), consumerGroup, eventType.name(), java.sql.Timestamp.from(Instant.now()));
		return inserted > 0;
	}

	/**
	 * 보관 주기가 지난 처리 이력 정리. idx_processed 를 탄다.
	 * 보관 기간은 Kafka retention 보다 길어야 한다 — 짧으면 재전달을 신규로 착각해 이중 청구가 난다.
	 */
	public int purgeProcessedBefore(Instant threshold, int batchSize) {
		return jdbcTemplate.update(PURGE, java.sql.Timestamp.from(threshold), batchSize);
	}
}
