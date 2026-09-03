package com.urban6.order.infra.persistence;

import com.urban6.order.domain.SagaInstance;
import com.urban6.order.domain.SagaStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SagaInstanceRepository extends JpaRepository<SagaInstance, UUID> {

	Optional<SagaInstance> findByOrderNo(String orderNo);

	/**
	 * 사가 상태 전이. step_started_at 은 건드리지 않는다 — 단계는 그대로고 상태만 바뀐다.
	 * 여기서 갱신하면 Stuck 탐지가 "이 단계에 얼마나 머물렀나" 를 잘못 잰다.
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

	/**
	 * Stuck 후보만 뽑는다. 최종 판정은 호출자가 단계별 임계값으로 한다.
	 * idx_stuck (status, step_started_at) 을 타고 step_started_at ASC 로 준다 —
	 * Pageable 로 잘려도 첫 행이 가장 오래된 것이라 "가장 오래된 정체의 나이" 는 항상 정확하다.
	 */
	@Query("""
			select s
			  from SagaInstance s
			 where s.status in :statuses
			   and s.stepStartedAt < :threshold
			 order by s.stepStartedAt asc
			""")
	List<SagaInstance> findStuckCandidates(@Param("statuses") Collection<SagaStatus> statuses,
										   @Param("threshold") Instant threshold,
										   Pageable pageable);
}
