package com.urban6.payment.infra.messaging;

import java.time.Instant;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/** 선점과 실제 처리는 같은 트랜잭션이어야 한다 — 분리하면 롤백돼도 선점만 남아 영원히 재처리되지 않는다. */
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

	public boolean claim(UUID messageId, String consumerGroup, String eventType) {
		int inserted = jdbcTemplate.update(CLAIM,
				messageId.toString(), consumerGroup, eventType, java.sql.Timestamp.from(Instant.now()));
		return inserted > 0;
	}

	/** 보관 기간은 Kafka retention 보다 길어야 한다 — 짧으면 재전달을 신규로 착각해 이중 청구가 난다. */
	public int purgeProcessedBefore(Instant threshold, int batchSize) {
		return jdbcTemplate.update(PURGE, java.sql.Timestamp.from(threshold), batchSize);
	}
}
