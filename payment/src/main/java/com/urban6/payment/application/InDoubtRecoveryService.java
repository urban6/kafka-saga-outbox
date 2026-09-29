package com.urban6.payment.application;

import com.urban6.payment.config.InDoubtProperties;
import com.urban6.payment.domain.Payment;
import com.urban6.payment.infra.client.PgChargeResult;
import com.urban6.payment.infra.client.PgClient;
import com.urban6.payment.infra.persistence.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 재청구하지 않고 조회로만 해소한다 — 빌링은 언제든 청구가 통해 재청구가 곧 이중 결제다.
 * 확정은 건별 트랜잭션이다. 묶으면 마지막 한 건 실패에 앞의 확정이 함께 날아간다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InDoubtRecoveryService {

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
			// 첫 행이 가장 오래된 것이다(updated_at ASC).
			log.error("in-doubt payments need manual review. count={} oldestOrderNo={} oldestAgeSeconds={}",
					escalated, inDoubt.getFirst().getOrderNo(),
					Duration.between(inDoubt.getFirst().getUpdatedAt(), now).toSeconds());
		}
	}

	private Resolution resolve(Payment payment, Instant now) {
		String orderNo = payment.getOrderNo();
		PgChargeResult result = pgClient.reconcile(orderNo);

		return switch (result.outcome()) {
			case APPROVED, REJECTED -> settle(payment, result);

			// PG 에 기록이 없다. customerId 가 없어 재청구할 수 없고, pivot 이전이라 거절(보상)이 정당하다.
			case RETRYABLE -> settle(payment,
					PgChargeResult.rejected(PG_NO_RECORD, "PG 에 결제 기록이 없어 미체결로 확정합니다."));

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
