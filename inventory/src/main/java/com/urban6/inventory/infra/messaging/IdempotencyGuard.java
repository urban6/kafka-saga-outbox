package com.urban6.inventory.infra.messaging;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 컨슈머 중복 처리 방지.
 * <p>
 * 아웃박스는 at-least-once 라 같은 메시지가 두 번 올 수 있다. 처리 직전에
 * {@code consumed_message} 에 (messageId, consumerGroup) 을 선점해 이미 처리한 메시지면 건너뛴다.
 * <p>
 * 두 가지가 중요하다.
 * <ul>
 *   <li>SELECT 로 먼저 확인하지 않는다. 확인과 INSERT 사이에 다른 컨슈머가 끼어들 수 있어서,
 *       INSERT 의 원자성에 맡기는 게 유일하게 안전하다</li>
 *   <li>선점과 실제 처리가 <b>같은 트랜잭션</b>이어야 한다. 분리하면 처리 중 롤백돼도 선점만 남아
 *       그 메시지는 영원히 재처리되지 않는다</li>
 * </ul>
 */
public class IdempotencyGuard {

	// MariaDB/MySQL 전용. 이미 있는 행이면 0을 돌려주고 조용히 넘어간다.
	private static final String CLAIM = """
			insert ignore into consumed_message
			    (message_id, consumer_group, event_type, processed_at)
			values (?, ?, ?, ?)
			""";

	private static final String PURGE = "delete from consumed_message where processed_at < ?";

	private final JdbcTemplate jdbcTemplate;

	public IdempotencyGuard(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** 처음 처리하는 메시지면 true. false 면 호출부는 아무것도 하지 않고 즉시 끝낸다. */
	public boolean claim(UUID messageId, String consumerGroup, String eventType) {
		int inserted = jdbcTemplate.update(CLAIM,
				messageId.toString(), consumerGroup, eventType, Timestamp.from(Instant.now()));
		return inserted > 0;
	}

	/** 보관 주기가 지난 처리 이력 정리. 보관 기간은 카프카 retention 보다 길어야 한다. */
	public int purgeProcessedBefore(Instant threshold) {
		return jdbcTemplate.update(PURGE, Timestamp.from(threshold));
	}
}
