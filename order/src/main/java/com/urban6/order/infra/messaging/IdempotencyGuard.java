package com.urban6.order.infra.messaging;

import java.time.Instant;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 컨슈머 중복 처리 방지.
 *
 * 아웃박스는 at-least-once 라 같은 메시지가 두 번 올 수 있다. 처리 직전에
 * consumed_message 에 (messageId, consumerGroup) 을 선점해서, 이미 처리한 메시지면 건너뛴다.
 * 선점 INSERT 와 실제 처리는 같은 트랜잭션이어야 한다. 처리 도중 롤백되면 선점도 같이 풀려야
 * 재시도가 가능하기 때문이다.
 *
 * 서비스 모듈에서 빈으로 등록하려면 config 패키지에서 @Bean 으로 만들거나
 * com.urban6.outbox 를 컴포넌트 스캔 대상에 넣는다.
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
	 *
	 * 보관 기간은 Kafka retention 보다 길어야 한다. 짧으면 재전달된 메시지를 처음 보는 것으로 착각해
	 * 중복 처리한다 — 결제라면 이중 청구다.
	 *
	 * limit 으로 한 번에 지우는 양을 묶는다. 무제한 DELETE 는 락을 오래 잡는다.
	 */
	public int purgeProcessedBefore(Instant threshold, int batchSize) {
		return jdbcTemplate.update(PURGE, java.sql.Timestamp.from(threshold), batchSize);
	}
}
