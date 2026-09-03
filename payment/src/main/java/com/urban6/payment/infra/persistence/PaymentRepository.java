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
	 * 결과를 모르는 채 남아 있는 결제. {@code idx_in_doubt (status, updated_at)} 를 탄다.
	 * <p>
	 * 파생 쿼리를 감싸는 이유는 이름이다 — 호출부가 {@code IN_PROGRESS} 라는 저장 표현이 아니라
	 * <b>"결과를 모른다"</b> 는 의미를 읽어야 한다.
	 * <p>
	 * {@code threshold} 로 갓 만들어진 행을 걸러낸다. PG 응답이 조금 늦게 도착할 수도 있는데
	 * 곧바로 조회하면 아직 처리 중인 결제를 미해결로 셀 뿐이다.
	 */
	default List<Payment> findInDoubtBefore(Instant threshold, Pageable pageable) {
		return findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
				PaymentStatus.IN_PROGRESS, threshold, pageable);
	}

	/**
	 * 조회로 승인이 확인된 결제를 확정한다.
	 * <p>
	 * {@code where status = IN_PROGRESS} 가 이중 확정을 막는다. 0건이면 누가 이미 옮긴 것이므로
	 * 호출부는 회신도 내지 않아야 한다 — 그쪽이 이미 냈다.
	 * <p>
	 * <b>{@code failure_code} 를 비운다.</b> 거기 남아 있는 {@code PG_TIMEOUT} 은 거절 근거가 아니라
	 * "왜 몰랐나" 였고, 지금은 알았다. {@code DONE} 인 행에 failure 가 붙어 있으면 컬럼 이름이 거짓말을 한다.
	 * <p>
	 * 이 결제가 in-doubt 를 거쳤다는 흔적은 로그에만 남는다. 영속 기록이 필요해지면
	 * {@code reconciled_at} 같은 컬럼을 따로 두는 게 맞다 — 실패 컬럼을 흔적 보관에 겸용하지 않는다.
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
	 * <p>
	 * {@code failure_code} 를 덮어쓰는 게 맞다 — 지금 들어 있는 건 거절 근거가 아니라
	 * {@code PG_TIMEOUT} 같은 "왜 몰랐나" 이고, 확정된 지금은 "왜 실패했나" 가 남아야 한다.
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
