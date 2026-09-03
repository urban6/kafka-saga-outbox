package com.urban6.payment.mockpg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * PG 의 결제 상태와 판정 로직. <b>인메모리다.</b>
 * <p>
 * payment_db 를 쓰지 않는 이유는 외부 시스템이기 때문이다. 같은 DB 를 쓰면 payment 서비스가
 * SQL 로 PG 상태를 들여다보는 지름길이 생기고, 그러면 조회 API 를 쓸 이유가 사라진다.
 * 재시작하면 날아가지만 이 프로젝트에서는 문제가 되지 않는다.
 * <p>
 * 결제 하나의 수명은 Toss 와 같다 — {@link #authenticate 인증}(결제창) 으로 {@code IN_PROGRESS} 가 되고,
 * 유효시간 안에 {@link #confirm 승인} 이 오면 {@code DONE}, 안 오면 {@code EXPIRED} 다.
 * 돈이 빠지는 건 승인 시점이고, 인증만 하고 떠난 결제는 아무 일도 일어나지 않는다.
 */
@Component
public class MockPgEngine {

	private static final Logger log = LoggerFactory.getLogger(MockPgEngine.class);

	/** Toss 결제 상태. {@code READY} 는 결제창을 열기만 한 상태라 이 Mock 에선 도달하지 않는다. */
	public enum PgStatus {
		READY, IN_PROGRESS, DONE, CANCELED, ABORTED, EXPIRED
	}

	/**
	 * PG 가 들고 있는 결제 1건. 응답 본문으로 그대로 나간다.
	 *
	 * @param requestedAt Toss {@code Payment.requestedAt}. 인증이 끝난 시각이며 승인 유효시간의 기준점이다
	 * @param approvedAt  승인 시각. {@code DONE} 일 때만 값이 있다
	 */
	public record PgPayment(
			String paymentKey,
			String orderId,
			PgStatus status,
			BigDecimal totalAmount,
			String method,
			Instant requestedAt,
			Instant approvedAt
	) {
		PgPayment with(PgStatus status, Instant approvedAt) {
			return new PgPayment(paymentKey, orderId, status, totalAmount, method, requestedAt, approvedAt);
		}
	}

	private final Map<String, PgPayment> byOrderId = new ConcurrentHashMap<>();

	/**
	 * 승인 처리 중인 주문. 실제 Toss 에선 멱등키 레이어가 하는 일이다 —
	 * 같은 주문의 승인 요청이 겹치면 두 번째는 {@code IDEMPOTENT_REQUEST_PROCESSING}(409) 을 받는다.
	 * 결제 상태({@code IN_PROGRESS})와 분리한 이유는 그 상태가 "인증 끝, 승인 대기" 를 뜻하기 때문이다.
	 */
	private final Set<String> confirming = ConcurrentHashMap.newKeySet();

	private final MockPgFaults faults;

	/** 인증 후 승인까지의 유효시간. Toss 문서 기준 10분. 만료 경로를 실측할 때 몇 초로 줄인다. */
	private final Duration sessionTtl;

	public MockPgEngine(MockPgFaults faults,
			@Value("${mockpg.session-ttl:10m}") Duration sessionTtl) {
		this.faults = faults;
		this.sessionTtl = sessionTtl;
	}

	/**
	 * 결제창 대역. 실제로는 브라우저에서 카드사 인증이 끝나면 Toss 가 {@code paymentKey} 를 발급해
	 * {@code successUrl} 로 보내는데, 여기엔 브라우저가 없으므로 이 호출이 그 자리를 대신한다.
	 * <p>
	 * 이미 {@code DONE} 인 주문은 거부한다. 그 외(인증만 하고 이탈, 거절, 만료)는 새 인증이 이전 것을
	 * 대체한다 — 사용자가 결제창을 다시 연 것이고, 이전 {@code paymentKey} 는 그 순간 무효가 된다.
	 */
	public PgPayment authenticate(String orderId, BigDecimal amount) {
		String paymentKey = "tgen_" + UUID.randomUUID().toString().replace("-", "");
		PgPayment authenticated = new PgPayment(
				paymentKey, orderId, PgStatus.IN_PROGRESS, amount, "카드", Instant.now(), null);

		byOrderId.compute(orderId, (key, existing) -> {
			if (existing != null && existing.status() == PgStatus.DONE) {
				throw new PgApiException(HttpStatus.BAD_REQUEST,
						"ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다.");
			}
			return authenticated;
		});

		log.info("payment authenticated. orderId={} paymentKey={} amount={}", orderId, paymentKey, amount);
		return authenticated;
	}

	/**
	 * 결제 승인. 인증된 결제가 있어야 하고, 키와 금액이 인증 때와 같아야 한다.
	 * <p>
	 * 검사를 통과한 뒤 <b>{@link #confirming} 선점을 먼저</b> 한다. 지연을 주입하는 순간 이게 의미를 갖는다 —
	 * 지연 중에 들어온 두 번째 요청이 "처리 중" 응답을 받아야 한다.
	 */
	public PgPayment confirm(String paymentKey, String orderId, BigDecimal amount) {
		PgPayment existing = byOrderId.get(orderId);
		if (existing == null) {
			throw new PgApiException(HttpStatus.NOT_FOUND,
					"NOT_FOUND_PAYMENT", "존재하지 않는 결제 입니다.");
		}
		existing = expireIfNeeded(existing);

		switch (existing.status()) {
			// 실패가 아니라 성공으로 매핑해야 하는 그 코드다. 클라이언트가 이걸 실패로 보면
			// 이미 받은 돈에 대해 보상이 돌아간다.
			case DONE -> throw new PgApiException(HttpStatus.BAD_REQUEST,
					"ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다.");
			case EXPIRED -> throw new PgApiException(HttpStatus.NOT_FOUND,
					"NOT_FOUND_PAYMENT_SESSION", "결제 시간이 만료되어 결제 진행 데이터가 존재하지 않습니다.");
			case ABORTED, CANCELED -> throw new PgApiException(HttpStatus.BAD_REQUEST,
					"REJECT_CARD_COMPANY", "카드사에서 승인을 거절했습니다.");
			case READY, IN_PROGRESS -> { /* 승인 가능 */ }
		}

		// 인증 때 발급한 키가 아니다. PG 입장에선 그 키의 결제가 없는 것이다.
		if (!existing.paymentKey().equals(paymentKey)) {
			throw new PgApiException(HttpStatus.NOT_FOUND,
					"NOT_FOUND_PAYMENT", "존재하지 않는 결제 입니다.");
		}
		// 인증된 금액과 다르다. 실제 Toss 가 이 경우 내는 코드는 문서에서 확인하지 못했다.
		if (existing.totalAmount().compareTo(amount) != 0) {
			throw new PgApiException(HttpStatus.BAD_REQUEST,
					"INVALID_REQUEST", "결제 금액이 인증된 금액과 다릅니다.");
		}

		if (!confirming.add(orderId)) {
			throw new PgApiException(HttpStatus.CONFLICT,
					"IDEMPOTENT_REQUEST_PROCESSING", "멱등키 요청이 처리중입니다.");
		}
		try {
			// 선점 뒤, 판정 앞. 이 자리여야 두 가지가 동시에 재현된다.
			//  1) 지연 중에 들어온 재시도는 선점을 보고 409 를 받는다
			//  2) 지연이 클라이언트 read-timeout 을 넘으면 클라이언트는 결과를 모르는데
			//     아래에서 PG 는 DONE 으로 확정한다 = in-doubt 그 자체
			delayIfInjected(orderId);

			// 상태는 IN_PROGRESS 그대로 둔다. 이 Mock 의 500 은 "요청이 처리되기 전에 실패했다" 로
			// 모델링한다 — 그래야 재시도가 성공으로 이어져 RETRYABLE 판정을 검증할 수 있다.
			if (roll(faults.getErrorRate())) {
				log.info("pg internal error injected. orderId={}", orderId);
				throw new PgApiException(HttpStatus.INTERNAL_SERVER_ERROR,
						"FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING", "결제가 완료되지 않았어요. 다시 시도해주세요.");
			}

			if (roll(faults.getRejectRate())) {
				byOrderId.put(orderId, existing.with(PgStatus.ABORTED, null));
				log.info("payment rejected. orderId={}", orderId);
				throw new PgApiException(HttpStatus.BAD_REQUEST,
						"REJECT_CARD_COMPANY", "카드사에서 승인을 거절했습니다.");
			}

			PgPayment approved = existing.with(PgStatus.DONE, Instant.now());
			byOrderId.put(orderId, approved);
			log.info("payment approved. orderId={} paymentKey={}", orderId, paymentKey);
			return approved;
		} finally {
			confirming.remove(orderId);
		}
	}

	/**
	 * 주문번호로 결제 조회. 타임아웃이 났을 때 무작정 재승인하지 않고 여기부터 보게 된다.
	 * 그 방어 코드는 아직 없지만, API 는 처음부터 있어야 한다.
	 */
	public PgPayment findByOrderId(String orderId) {
		PgPayment payment = byOrderId.get(orderId);
		if (payment == null) {
			throw new PgApiException(HttpStatus.NOT_FOUND,
					"NOT_FOUND_PAYMENT", "존재하지 않는 결제 입니다.");
		}
		return expireIfNeeded(payment);
	}

	/**
	 * 만료는 <b>읽을 때</b> 판정한다. 스케줄러로 상태를 바꾸면 Mock 이 필요 이상으로 커진다.
	 * 유효시간 안에 아무도 안 보면 영원히 {@code IN_PROGRESS} 로 남지만, 누군가 보는 순간 결과는 같다.
	 */
	private PgPayment expireIfNeeded(PgPayment payment) {
		if (payment.status() != PgStatus.IN_PROGRESS
				|| payment.requestedAt().plus(sessionTtl).isAfter(Instant.now())) {
			return payment;
		}
		PgPayment expired = payment.with(PgStatus.EXPIRED, null);
		byOrderId.put(payment.orderId(), expired);
		log.info("payment session expired. orderId={} requestedAt={} ttl={}",
				payment.orderId(), payment.requestedAt(), sessionTtl);
		return expired;
	}

	/**
	 * 요청 스레드를 실제로 묶는다. 이게 진짜 느린 PG 다 — 논블로킹으로 흉내내면 클라이언트 쪽
	 * 소켓 read-timeout 이 발동하지 않아 검증할 게 없어진다.
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
