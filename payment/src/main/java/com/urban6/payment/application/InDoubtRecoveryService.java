package com.urban6.payment.application;

import com.urban6.payment.config.InDoubtProperties;
import com.urban6.payment.domain.Payment;
import com.urban6.payment.infra.client.PgChargeResult;
import com.urban6.payment.infra.client.PgClient;
import com.urban6.payment.infra.persistence.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * "돈이 빠졌는지 모르는" 결제를 조회 API 로 해소한다.
 *
 * 재청구하지 않는다. 빌링은 인증 세션이 없어 언제든 청구가 통하므로, PG 의 멱등키 보관 기간이
 * 지난 뒤 재청구하면 그건 이중 결제다. 물어보는 것만이 안전하다.
 *
 * @Transactional 이 없다. 행마다 PG 를 부르기 때문이다.
 * DB 확정은 PaymentTransactionService.settle 이 건별 트랜잭션으로 맡는다 —
 * 한 트랜잭션으로 묶으면 50건 중 마지막이 실패할 때 앞의 49건 확정이 함께 날아간다.
 */
@Service
@RequiredArgsConstructor
public class InDoubtRecoveryService {

	private static final Logger log = LoggerFactory.getLogger(InDoubtRecoveryService.class);

	/** PG 에 기록이 없을 때 남기는 코드. 미체결이 확인됐다는 뜻이다. */
	static final String PG_NO_RECORD = "PG_NO_RECORD";

	private enum Resolution { SETTLED, PENDING, ESCALATED }

	private final PaymentRepository paymentRepository;
	private final PgClient pgClient;
	private final PaymentTransactionService paymentTransactionService;
	private final InDoubtProperties properties;

	@Scheduled(fixedDelayString = "${payment.in-doubt.scan-interval}")
	public void recover() {
		Instant now = Instant.now();

		List<Payment> inDoubt = paymentRepository.findInDoubtBefore(
				now.minus(properties.grace()), PageRequest.of(0, properties.scanLimit()));

		if (inDoubt.isEmpty()) {
			return;
		}

		int settled = 0;
		int pending = 0;
		int escalated = 0;
		for (Payment payment : inDoubt) {
			switch (resolve(payment, now)) {
				case SETTLED -> settled++;
				case PENDING -> pending++;
				case ESCALATED -> escalated++;
			}
		}

		log.info("in-doubt recovery done. scanned={} settled={} pending={} escalated={}",
				inDoubt.size(), settled, pending, escalated);

		if (escalated > 0) {
			// 조회를 반복해도 안 풀린 결제다. 돈이 걸려 있고 자동으로는 더 할 게 없다.
			// 가장 오래된 것이 첫 행이다 — 조회가 updated_at ASC 로 오기 때문.
			log.error("in-doubt payments need manual review. count={} oldestOrderNo={} oldestAgeSeconds={}",
					escalated, inDoubt.getFirst().getOrderNo(),
					Duration.between(inDoubt.getFirst().getUpdatedAt(), now).toSeconds());
		}
	}

	/** PG 조회는 트랜잭션 밖이다. 확정만 트랜잭션으로 들어간다. */
	private Resolution resolve(Payment payment, Instant now) {
		String orderNo = payment.getOrderNo();
		PgChargeResult result = pgClient.reconcile(orderNo);

		return switch (result.outcome()) {
			case APPROVED, REJECTED -> settle(payment, result);

			// PG 에 기록이 없다 = 청구가 아예 안 닿았다. 돈이 안 빠진 게 확인됐다.
			//
			// 재청구하지 않고 거절로 확정하는 이유: payment 행에 customerId 가 없어 커맨드를 다시 만들 수 없다.
			// 그리고 pivot 이전이라 보상(주문 취소 + 재고 해제)이 정당하다 — 고객은 다시 주문하면 된다.
			// (재청구까지 하려면 payment 에 customer_id 를 두거나 사가가 커맨드를 재발행해야 한다)
			case RETRYABLE -> settle(payment,
					PgChargeResult.rejected(PG_NO_RECORD, "PG 에 결제 기록이 없어 미체결로 확정합니다."));

			// 여전히 모른다. PG 가 아직 처리 중이거나 조회 자체가 실패했다.
			case IN_DOUBT -> stillInDoubt(payment, now, result);
		};
	}

	private Resolution settle(Payment payment, PgChargeResult result) {
		return paymentTransactionService.settle(payment, result)
				? Resolution.SETTLED
				: Resolution.PENDING;
	}

	private Resolution stillInDoubt(Payment payment, Instant now, PgChargeResult result) {
		Duration age = Duration.between(payment.getUpdatedAt(), now);
		if (age.compareTo(properties.escalateAfter()) >= 0) {
			return Resolution.ESCALATED;
		}
		log.info("still in doubt. orderNo={} ageSeconds={} code={}",
				payment.getOrderNo(), age.toSeconds(), result.failureCode());
		return Resolution.PENDING;
	}
}
