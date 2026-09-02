package com.urban6.order.infra.persistence;

import com.urban6.order.domain.SagaInstance;
import com.urban6.order.domain.SagaStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SagaInstanceRepository extends JpaRepository<SagaInstance, UUID> {

	Optional<SagaInstance> findByOrderNo(String orderNo);

	/**
	 * 사가 상태 전이. {@code step_started_at} 은 건드리지 않는다 —
	 * 단계는 그대로고 상태만 바뀌기 때문이다. 여기서 갱신해버리면 Stuck 탐지가
	 * "이 단계에 얼마나 머물렀나"를 잘못 재게 된다.
	 */
	@Modifying(flushAutomatically = true)
	@Query("""
			update SagaInstance s
			   set s.status    = :next,
			       s.updatedAt = :now
			 where s.sagaId = :sagaId
			   and s.status  = :expected
			""")
	int transitionStatus(@Param("sagaId") UUID sagaId,
						 @Param("expected") SagaStatus expected,
						 @Param("next") SagaStatus next,
						 @Param("now") Instant now);
}
