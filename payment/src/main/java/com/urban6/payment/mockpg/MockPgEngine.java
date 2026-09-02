package com.urban6.payment.mockpg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * PG 의 결제 상태와 판정 로직. <b>인메모리다.</b>
 * <p>
 * payment_db 를 쓰지 않는 이유는 외부 시스템이기 때문이다. 같은 DB 를 쓰면 payment 서비스가
 * SQL 로 PG 상태를 들여다보는 지름길이 생기고, 그러면 조회 API 를 쓸 이유가 사라진다.
 * 재시작하면 날아가지만 이 프로젝트에서는 문제가 되지 않는다.
 */
@Component
public class MockPgEngine {

	private static final Logger log = LoggerFactory.getLogger(MockPgEngine.class);

	/** Toss 결제 상태. 지금 도달하는 값은 DONE / ABORTED 둘이다. */
	public enum PgStatus {
		READY, IN_PROGRESS, DONE, CANCELED, ABORTED, EXPIRED
	}

	/** PG 가 들고 있는 결제 1건. 응답 본문으로 그대로 나간다. */
	public record PgPayment(
			String paymentKey,
			String orderId,
			PgStatus status,
			BigDecimal totalAmount,
			String method,
			Instant approvedAt
	) {
	}

	private final Map<String, PgPayment> byOrderId = new ConcurrentHashMap<>();
	private final MockPgFaults faults;

	public MockPgEngine(MockPgFaults faults) {
		this.faults = faults;
	}

	/**
	 * 결제 승인.
	 * <p>
	 * 판정보다 <b>선점을 먼저</b> 한다({@code putIfAbsent}). 지연을 주입하는 순간 이게 의미를 갖는다 —
	 * 지연 중에 들어온 두 번째 요청이 {@code IN_PROGRESS} 를 보고 "처리 중" 응답을 받아야 한다.
	 */
	public PgPayment confirm(String paymentKey, String orderId, BigDecimal amount) {
		PgPayment claimed = new PgPayment(paymentKey, orderId, PgStatus.IN_PROGRESS, amount, "카드", null);
		PgPayment existing = byOrderId.putIfAbsent(orderId, claimed);

		if (existing != null) {
			throw switch (existing.status()) {
				// 실패가 아니라 성공으로 매핑해야 하는 그 코드다. 클라이언트가 이걸 실패로 보면
				// 이미 받은 돈에 대해 보상이 돌아간다.
				case DONE -> new PgApiException(HttpStatus.BAD_REQUEST,
						"ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다.");
				case IN_PROGRESS -> new PgApiException(HttpStatus.CONFLICT,
						"IDEMPOTENT_REQUEST_PROCESSING", "멱등키 요청이 처리중입니다.");
				default -> new PgApiException(HttpStatus.BAD_REQUEST,
						"REJECT_CARD_COMPANY", "카드사에서 승인을 거절했습니다.");
			};
		}

		// 선점 뒤, 판정 앞. 이 자리여야 두 가지가 동시에 재현된다.
		//  1) 지연 중에 들어온 재시도는 IN_PROGRESS 를 보고 409 를 받는다
		//  2) 지연이 클라이언트 read-timeout 을 넘으면 클라이언트는 결과를 모르는데
		//     아래에서 PG 는 DONE 으로 확정한다 = in-doubt 그 자체
		delayIfInjected(orderId);

		// 선점을 되돌리고 던진다. 이 Mock 의 500 은 "요청이 처리되기 전에 실패했다" 로 모델링한다.
		// 그래야 재시도가 성공으로 이어져 RETRYABLE 판정을 검증할 수 있다.
		// ABORTED 로 남기면 재시도해도 영원히 거절이고, IN_PROGRESS 로 남기면 영원히 409 다.
		// 진짜 in-doubt(모름)는 위의 지연이 만든다.
		if (roll(faults.getErrorRate())) {
			byOrderId.remove(orderId);
			log.info("pg internal error injected. orderId={}", orderId);
			throw new PgApiException(HttpStatus.INTERNAL_SERVER_ERROR,
					"FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING", "결제가 완료되지 않았어요. 다시 시도해주세요.");
		}

		if (roll(faults.getRejectRate())) {
			byOrderId.put(orderId, withStatus(claimed, PgStatus.ABORTED, null));
			log.info("payment rejected. orderId={}", orderId);
			throw new PgApiException(HttpStatus.BAD_REQUEST,
					"REJECT_CARD_COMPANY", "카드사에서 승인을 거절했습니다.");
		}

		PgPayment approved = withStatus(claimed, PgStatus.DONE, Instant.now());
		byOrderId.put(orderId, approved);
		log.info("payment approved. orderId={} paymentKey={}", orderId, paymentKey);
		return approved;
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
		return payment;
	}

	/**
	 * 요청 스레드를 실제로 묶는다. 이게 진짜 느린 PG 다 — 논블로킹으로 흉내내면 클라이언트 쪽
	 * 소켓 read-timeout 이 발동하지 않아 검증할 게 없어진다.
	 * <p>
	 * 선점은 그대로 두고 나간다. 중단됐다고 되돌리면 "처리 중" 상태가 사라져
	 * 재시도가 새 결제를 만든다.
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

	private static PgPayment withStatus(PgPayment source, PgStatus status, Instant approvedAt) {
		return new PgPayment(source.paymentKey(), source.orderId(), status,
				source.totalAmount(), source.method(), approvedAt);
	}

	private static boolean roll(double rate) {
		return rate > 0 && ThreadLocalRandom.current().nextDouble() < rate;
	}
}
