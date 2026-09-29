package com.urban6.order.infra.messaging;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

	/** 커넥터가 안 읽은 행을 지우면 영영 발행되지 않는다. @Transactional 은 SimpleJpaRepository 의 readOnly 기본값 때문이다. */
	@Transactional
	@Modifying
	@Query(value = "delete from outbox where created_at < :threshold limit :batchSize", nativeQuery = true)
	int deleteByCreatedAtBefore(@Param("threshold") Instant threshold, @Param("batchSize") int batchSize);
}
