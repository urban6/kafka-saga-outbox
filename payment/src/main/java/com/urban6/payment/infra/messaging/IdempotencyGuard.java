package com.urban6.payment.infra.messaging;

import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 컨슈머 중복 처리 방지.
 * <p>
 * 아웃박스는 at-least-once 라 같은 메시지가 두 번 올 수 있다. 처리 직전에
 * {@code consumed_message} 에 (messageId, consumerGroup) 을 선점해서, 이미 처리한 메시지면 건너뛴다.
 * 선점 INSERT 와 실제 처리는 <b>같은 트랜잭션</b>이어야 한다. 처리 도중 롤백되면 선점도 같이 풀려야
 * 재시도가 가능하기 때문이다.
 * <p>
 * 서비스 모듈에서 빈으로 등록하려면 {@code config} 패키지에서 {@code @Bean} 으로 만들거나
 * {@code com.urban6.outbox} 를 컴포넌트 스캔 대상에 넣는다.
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

	/**
	 * 이 메시지를 처음 처리하는 것이면 true. 이미 처리한 메시지면 false 이므로 호출부는 즉시 ack 하고 끝낸다.
	 */
	public boolean claim(UUID messageId, String consumerGroup, String eventType) {
		int inserted = jdbcTemplate.update(CLAIM,
				messageId.toString(), consumerGroup, eventType, java.sql.Timestamp.from(Instant.now()));
		return inserted > 0;
	}

	/** 보관 주기가 지난 처리 이력 정리. idx_processed 를 탄다. */
	public int purgeProcessedBefore(Instant threshold) {
		return jdbcTemplate.update(PURGE, java.sql.Timestamp.from(threshold));
	}
}
