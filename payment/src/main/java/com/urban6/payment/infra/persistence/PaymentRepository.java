package com.urban6.payment.infra.persistence;

import com.urban6.payment.domain.Payment;
import com.urban6.payment.domain.PaymentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, String> {

	Optional<Payment> findByOrderNo(String orderNo);

	List<Payment> findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
			PaymentStatus status, Instant threshold, Pageable pageable);

	/**
	 * 결과를 모르는 채 남아 있는 결제. idx_in_doubt (status, updated_at) 를 탄다.
	 * 파생 쿼리를 감싼 건 이름 때문이다 — 호출부는 IN_PROGRESS 가 아니라 "결과를 모른다" 를 읽어야 한다.
	 * threshold 는 갓 만들어진 행을 걸러낸다. 곧바로 조회하면 처리 중인 결제를 미해결로 센다.
	 */
	default List<Payment> findInDoubtBefore(Instant threshold, Pageable pageable) {
		return findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
				PaymentStatus.IN_PROGRESS, threshold, pageable);
	}

	/**
	 * 조회로 승인이 확인된 결제를 확정한다. where status = IN_PROGRESS 가 이중 확정을 막는다 —
	 * 0건이면 누가 이미 옮긴 것이므로 호출부는 회신도 내지 않아야 한다.
	 *
	 * failure_code 를 비운다. 거기 남은 PG_TIMEOUT 은 거절 근거가 아니라 "왜 몰랐나" 였다.
	 */
	@Modifying(flushAutomatically = true)
	@Query("""
			update Payment p
			   set p.status        = com.urban6.payment.domain.PaymentStatus.DONE,
			       p.paymentKey    = :paymentKey,
			       p.failureCode   = null,
			       p.failureReason = null,
			       p.updatedAt     = :now
			 where p.paymentId = :paymentId
			   and p.status    = com.urban6.payment.domain.PaymentStatus.IN_PROGRESS
			""")
	int settleApproved(@Param("paymentId") String paymentId,
					   @Param("paymentKey") String paymentKey,
					   @Param("now") Instant now);

	/**
	 * 조회로 미체결이 확인된 결제를 거절로 확정한다.
	 * 승인 확정과 반대로 failure_code 를 덮어쓴다 — 확정된 지금은 "왜 실패했나" 가 남아야 한다.
	 */
	@Modifying(flushAutomatically = true)
	@Query("""
			update Payment p
			   set p.status        = com.urban6.payment.domain.PaymentStatus.ABORTED,
			       p.failureCode   = :failureCode,
			       p.failureReason = :failureReason,
			       p.updatedAt     = :now
			 where p.paymentId = :paymentId
			   and p.status    = com.urban6.payment.domain.PaymentStatus.IN_PROGRESS
			""")
	int settleRejected(@Param("paymentId") String paymentId,
					   @Param("failureCode") String failureCode,
					   @Param("failureReason") String failureReason,
					   @Param("now") Instant now);
}
