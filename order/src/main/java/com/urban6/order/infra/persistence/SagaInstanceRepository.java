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

	/**
	 * Stuck <b>후보</b>. 최종 판정은 호출자가 단계별 임계값으로 한다 —
	 * SQL 은 {@code step_started_at} 하나로만 자를 수 있는데 임계값은 단계마다 다르기 때문이다.
	 * 가장 짧은 임계값으로 넉넉히 뽑고 나머지는 메모리에서 거른다.
	 * <p>
	 * {@code statuses} 는 <b>비종료 상태 전부</b>({@code isTerminated()} 의 여집합)를 받는다.
	 * 나중에 원격 보상이 붙어 {@code COMPENSATING} 이 실제로 쓰일 때 여기에 추가하는 걸 잊으면,
	 * 보상이 멈췄는데 아무도 모르는 상태가 된다.
	 * <p>
	 * {@code idx_stuck (status, step_started_at)} 을 탄다. {@code IN} 이라 상태값마다 레인지 스캔이
	 * 나뉘고 정렬이 한 번 더 붙지만, {@code Pageable} 로 잘라 읽으므로 대상이 커져도 비용이 고정된다.
	 * <p>
	 * 가장 오래된 행이 항상 <b>첫 번째</b>라는 게 중요하다. 잘려도 "가장 오래된 정체의 나이"는 정확하다.
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
