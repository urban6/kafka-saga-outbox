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

	/** threshold 는 갓 만들어진 행을 걸러낸다. 곧바로 보면 처리 중인 결제를 미해결로 센다. */
	default List<Payment> findInDoubtBefore(Instant threshold, Pageable pageable) {
		return findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
				PaymentStatus.IN_PROGRESS, threshold, pageable);
	}

	/**
	 * WHERE status = IN_PROGRESS 가 이중 확정을 막는다. 0건이면 호출부는 회신도 내지 않는다.
	 * failure_code 를 비운다 — 남은 PG_TIMEOUT 은 거절 근거가 아니라 "왜 몰랐나" 였다.
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

	/** 승인 확정과 반대로 failure_code 를 덮어쓴다 — 확정 뒤엔 "왜 실패했나" 가 남아야 한다. */
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
