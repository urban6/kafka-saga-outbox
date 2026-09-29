package com.urban6.payment.mockpg;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** 외부 시스템 흉내라 payment_db 를 쓰지 않고 인메모리다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockPgEngine {

	/** 빌링에선 DONE 아니면 ABORTED 다. 나머지는 Toss 계약 호환용이다. */
	public enum PgStatus {
		READY, IN_PROGRESS, DONE, CANCELED, ABORTED, EXPIRED
	}

	public record PgPayment(
			String paymentKey,
			String orderId,
			PgStatus status,
			BigDecimal totalAmount,
			String method,
			Instant requestedAt,
			Instant approvedAt
	) {
	}

	public record BillingKey(
			String billingKey,
			String customerKey,
			String cardNumber,
			Instant authenticatedAt
	) {
		public String cardLast4() {
			return cardNumber.substring(cardNumber.length() - 4);
		}
	}

	/** 확률과 달리 시드만으로 보상 경로가 재현된다. */
	private static final String REJECT_CARD_SUFFIX = "0000";

	private final Map<String, BillingKey> byBillingKey = new ConcurrentHashMap<>();
	private final Map<String, PgPayment> byOrderId = new ConcurrentHashMap<>();

	/** Toss 멱등키 레이어 흉내. 겹치면 두 번째가 409 를 받는다. */
	private final Set<String> charging = ConcurrentHashMap.newKeySet();

	private final MockPgFaults faults;

	public BillingKey issueBillingKey(String customerKey, String cardNumber) {
		BillingKey issued = new BillingKey(
				"billing_" + UUID.randomUUID().toString().replace("-", ""),
				customerKey, cardNumber, Instant.now());
		byBillingKey.put(issued.billingKey(), issued);
		log.info("billing key issued. customerKey={} cardLast4={}", customerKey, issued.cardLast4());
		return issued;
	}

	/** ABORTED 였던 주문에 재청구가 오면 새 결제로 덮어쓴다 — 거절된 결제는 돈이 안 빠졌다. */
	public PgPayment charge(String billingKey, String customerKey, String orderId, BigDecimal amount) {
		BillingKey key = byBillingKey.get(billingKey);
		if (key == null || !key.customerKey().equals(customerKey)) {
			// Toss 실제 코드 미확인.
			throw new PgApiException(HttpStatus.NOT_FOUND,
					"NOT_FOUND_BILLING_KEY", "존재하지 않는 빌링키 입니다.");
		}

		PgPayment existing = byOrderId.get(orderId);
		if (existing != null && existing.status() == PgStatus.DONE) {
			throw new PgApiException(HttpStatus.BAD_REQUEST,
					"ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다.");
		}

		if (!charging.add(orderId)) {
			throw new PgApiException(HttpStatus.CONFLICT,
					"IDEMPOTENT_REQUEST_PROCESSING", "멱등키 요청이 처리중입니다.");
		}
		try {
			// 선점 뒤·판정 앞이어야 지연 중 재시도의 409 와, read-timeout 뒤 DONE 확정(in-doubt)이 함께 재현된다.
			delayIfInjected(orderId);

			// 500 은 "처리 전 실패" 로 모델링한다 — 그래야 재시도가 성공해 RETRYABLE 을 검증할 수 있다.
			if (roll(faults.getErrorRate())) {
				log.info("pg internal error injected. orderId={}", orderId);
				throw new PgApiException(HttpStatus.INTERNAL_SERVER_ERROR,
						"FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING", "결제가 완료되지 않았어요. 다시 시도해주세요.");
			}

			String paymentKey = "tgen_" + UUID.randomUUID().toString().replace("-", "");
			Instant now = Instant.now();

			if (key.cardNumber().endsWith(REJECT_CARD_SUFFIX) || roll(faults.getRejectRate())) {
				byOrderId.put(orderId, new PgPayment(paymentKey, orderId, PgStatus.ABORTED, amount, "카드", now, null));
				log.info("payment rejected. orderId={} cardLast4={}", orderId, key.cardLast4());
				throw new PgApiException(HttpStatus.BAD_REQUEST,
						"REJECT_CARD_COMPANY", "카드사에서 승인을 거절했습니다.");
			}

			PgPayment approved = new PgPayment(paymentKey, orderId, PgStatus.DONE, amount, "카드", now, now);
			byOrderId.put(orderId, approved);
			log.info("payment approved. orderId={} paymentKey={}", orderId, paymentKey);
			return approved;
		} finally {
			charging.remove(orderId);
		}
	}

	public PgPayment findByOrderId(String orderId) {
		PgPayment payment = byOrderId.get(orderId);
		if (payment == null) {
			throw new PgApiException(HttpStatus.NOT_FOUND,
					"NOT_FOUND_PAYMENT", "존재하지 않는 결제 입니다.");
		}
		return payment;
	}

	/** 스레드를 실제로 묶어야 클라이언트 read-timeout 이 발동한다. */
	private void delayIfInjected(String orderId) {
		Duration delay = faults.getDelay();
		if (delay.isZero() || delay.isNegative() || !roll(faults.getDelayRate())) {
			return;
		}

		log.info("pg delay injected. orderId={} delay={}", orderId, delay);
		try {
			Thread.sleep(delay.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("mock pg delay interrupted. orderId=" + orderId, e);
		}
	}

	private static boolean roll(double rate) {
		return rate > 0 && ThreadLocalRandom.current().nextDouble() < rate;
	}
}
