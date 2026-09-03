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

/**
 * PG 의 결제 상태와 판정 로직. 외부 시스템이라 payment_db 를 쓰지 않고 인메모리다.
 *
 * 빌링 계약이라 인증 단계가 없다 — 청구 한 번이 결제의 시작이자 끝이고 그 호출에서 돈이 빠진다.
 * 그래서 IN_PROGRESS·EXPIRED 에 도달하는 경로가 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockPgEngine {

	/** Toss 결제 상태. 빌링에선 DONE 아니면 ABORTED 다. 나머지는 계약 호환용으로만 남긴다. */
	public enum PgStatus {
		READY, IN_PROGRESS, DONE, CANCELED, ABORTED, EXPIRED
	}

	/** PG 가 들고 있는 결제 1건. 응답 본문으로 그대로 나간다. approvedAt 은 DONE 일 때만 있다. */
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

	/** PG 가 보관하는 빌링키 1건. 카드번호는 거절 규칙 판정에만 쓰고 응답엔 끝 4자리만 나간다. */
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

	/** 거절 규칙. 이 끝자리 카드는 항상 카드사 거절이다 — 확률과 달리 시드만으로 보상 경로가 재현된다. */
	private static final String REJECT_CARD_SUFFIX = "0000";

	private final Map<String, BillingKey> byBillingKey = new ConcurrentHashMap<>();
	private final Map<String, PgPayment> byOrderId = new ConcurrentHashMap<>();

	/** 청구 처리 중인 주문. 실제 Toss 의 멱등키 레이어를 흉내낸다 — 겹치면 두 번째가 409 를 받는다. */
	private final Set<String> charging = ConcurrentHashMap.newKeySet();

	private final MockPgFaults faults;

	/**
	 * Toss POST /v1/billing/authorizations/card.
	 * 재등록하면 새 키가 나오고 이전 키도 그대로 유효하다(Toss 동일).
	 */
	public BillingKey issueBillingKey(String customerKey, String cardNumber) {
		BillingKey issued = new BillingKey(
				"billing_" + UUID.randomUUID().toString().replace("-", ""),
				customerKey, cardNumber, Instant.now());
		byBillingKey.put(issued.billingKey(), issued);
		log.info("billing key issued. customerKey={} cardLast4={}", customerKey, issued.cardLast4());
		return issued;
	}

	/**
	 * Toss POST /v1/billing/{billingKey}. 여기서 돈이 빠진다.
	 * ABORTED 였던 주문에 재청구가 오면 새 결제로 덮어쓴다 — 거절된 결제는 돈이 안 빠졌다.
	 */
	public PgPayment charge(String billingKey, String customerKey, String orderId, BigDecimal amount) {
		BillingKey key = byBillingKey.get(billingKey);
		if (key == null || !key.customerKey().equals(customerKey)) {
			// Toss 실제 코드 미확인. 키가 없거나 다른 고객의 키면 PG 입장에선 같은 상황이다.
			throw new PgApiException(HttpStatus.NOT_FOUND,
					"NOT_FOUND_BILLING_KEY", "존재하지 않는 빌링키 입니다.");
		}

		PgPayment existing = byOrderId.get(orderId);
		if (existing != null && existing.status() == PgStatus.DONE) {
			// 실패가 아니라 성공으로 매핑해야 하는 그 코드다. 클라이언트가 이걸 실패로 보면
			// 이미 받은 돈에 대해 보상이 돌아간다.
			throw new PgApiException(HttpStatus.BAD_REQUEST,
					"ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다.");
		}

		if (!charging.add(orderId)) {
			throw new PgApiException(HttpStatus.CONFLICT,
					"IDEMPOTENT_REQUEST_PROCESSING", "멱등키 요청이 처리중입니다.");
		}
		try {
			// 선점 뒤, 판정 앞. 이 자리여야 두 가지가 동시에 재현된다.
			//  1) 지연 중에 들어온 재시도는 선점을 보고 409 를 받는다
			//  2) 지연이 클라이언트 read-timeout 을 넘으면 클라이언트는 결과를 모르는데
			//     아래에서 PG 는 DONE 으로 확정한다 = in-doubt 그 자체
			delayIfInjected(orderId);

			// 이 Mock 의 500 은 "요청이 처리되기 전에 실패했다" 로 모델링한다 —
			// 그래야 재시도가 성공으로 이어져 RETRYABLE 판정을 검증할 수 있다.
			if (roll(faults.getErrorRate())) {
				log.info("pg internal error injected. orderId={}", orderId);
				throw new PgApiException(HttpStatus.INTERNAL_SERVER_ERROR,
						"FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING", "결제가 완료되지 않았어요. 다시 시도해주세요.");
			}

			String paymentKey = "tgen_" + UUID.randomUUID().toString().replace("-", "");
			Instant now = Instant.now();

			// 카드번호 규칙이 먼저다. 확률 거절은 그 다음 — 규칙은 재현용, 확률은 부하 실험용이다.
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

	/** 주문번호로 결제 조회. 타임아웃이 났을 때 무작정 재청구하지 않고 여기부터 보게 된다. */
	public PgPayment findByOrderId(String orderId) {
		PgPayment payment = byOrderId.get(orderId);
		if (payment == null) {
			throw new PgApiException(HttpStatus.NOT_FOUND,
					"NOT_FOUND_PAYMENT", "존재하지 않는 결제 입니다.");
		}
		return payment;
	}

	/**
	 * 요청 스레드를 실제로 묶는다. 논블로킹으로 흉내내면 클라이언트 소켓의
	 * read-timeout 이 발동하지 않아 in-doubt 검증이 통째로 무의미해진다.
	 */
	private void delayIfInjected(String orderId) {
		Duration delay = faults.getDelay();
		if (delay.isZero() || delay.isNegative() || !roll(faults.getDelayRate())) {
			return;
		}

		log.info("pg delay injected. orderId={} delay={}", orderId, delay);
		try {
			Thread.sleep(delay.toMillis());
		} catch (InterruptedException e) {
			// 삼키면 상위가 중단 요청을 못 본다. 플래그를 되살리고 던진다.
			Thread.currentThread().interrupt();
			throw new IllegalStateException("mock pg delay interrupted. orderId=" + orderId, e);
		}
	}

	private static boolean roll(double rate) {
		return rate > 0 && ThreadLocalRandom.current().nextDouble() < rate;
	}
}
