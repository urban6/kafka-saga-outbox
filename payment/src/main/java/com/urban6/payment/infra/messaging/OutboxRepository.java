package com.urban6.payment.infra.messaging;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 아웃박스 쓰기와 보관 정리용.
 * <p>
 * 발행은 Debezium 이 binlog 로 처리하므로 조회(폴링) 쿼리는 없다. 애플리케이션은 INSERT 만 하고,
 * 이미 발행된 오래된 행을 주기적으로 지우는 것만 담당한다.
 * <p>
 * 서비스 모듈에서 쓰려면 {@code @EnableJpaRepositories}/{@code @EntityScan} 에
 * {@code com.urban6.outbox} 패키지를 포함시켜야 한다.
 */
public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

	/**
	 * 보관 주기가 지난 행 정리. idx_created 를 탄다.
	 * <p>
	 * 커넥터가 아직 읽지 않은 행까지 지우면 그 이벤트는 영영 발행되지 않는다. 임계값은 커넥터 지연보다
	 * 충분히 길게(예: 수 시간) 잡는다.
	 */
	@Modifying
	@Query("delete from OutboxMessage m where m.createdAt < :threshold")
	int deleteByCreatedAtBefore(@Param("threshold") Instant threshold);
}
