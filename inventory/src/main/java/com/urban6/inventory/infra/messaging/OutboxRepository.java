package com.urban6.inventory.infra.messaging;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 아웃박스 쓰기와 보관 정리용. 발행은 Debezium 이 binlog 로 처리하므로 폴링 조회가 없다.
 */
public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

	/**
	 * 보관 주기가 지난 행 정리. 커넥터가 아직 읽지 않은 행을 지우면 그 이벤트는 영영 발행되지 않으므로
	 * 임계값은 커넥터 지연보다 충분히 길게 잡는다.
	 */
	@Modifying
	@Query("delete from OutboxMessage m where m.createdAt < :threshold")
	int deleteByCreatedAtBefore(@Param("threshold") Instant threshold);
}
