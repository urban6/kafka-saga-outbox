package com.urban6.payment.application;

import com.urban6.payment.domain.BillingKey;
import com.urban6.payment.domain.Payment;
import com.urban6.payment.infra.client.PgChargeResult;
import com.urban6.payment.infra.client.PgClient;
import com.urban6.payment.infra.client.PgRetryableException;
import com.urban6.payment.infra.persistence.BillingKeyRepository;
import com.urban6.payment.infra.persistence.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 결제 승인 유스케이스. HTTP(POST /api/payments)와 Kafka(payment.commands) 둘 다 여기로 온다.
 *
 * @Transactional 이 없다. 안에서 PG 를 호출하기 때문이다 — 외부 I/O 가 트랜잭션에 들어오면
 * PG 가 느린 만큼 DB 커넥션이 잡혀 있는다. DB 확정은 PaymentTransactionService 가 맡는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovePaymentService {

	/** PG 를 부르기 전에 우리가 내는 거절 코드. Toss 코드와 섞여 failure_code 에 남는다. */
	static final String NO_BILLING_KEY = "NO_BILLING_KEY";

	private final PaymentRepository paymentRepository;
	private final BillingKeyRepository billingKeyRepository;
	private final PgClient pgClient;
	private final PaymentTransactionService paymentTransactionService;

	/** HTTP 진입. 사가와 무관하므로 회신을 내지 않는다. */
	public Payment approve(String orderNo, String customerId, BigDecimal amount) {
		return process(orderNo, customerId, amount, null);
	}

	/** Kafka 진입. eventId 가 있으면 멱등 선점과 회신 적재까지 한다. */
	public Payment approve(UUID eventId, String orderNo, String customerId, BigDecimal amount) {
		return process(orderNo, customerId, amount, eventId);
	}

	private Payment process(String orderNo, String customerId, BigDecimal amount, UUID eventId) {
		// 이미 처리된 주문이면 PG 를 다시 부르지 않는다. 다만 회신은 다시 낸다 — 앞선 회신이 유실됐을 수 있다.
		//
		// IN_PROGRESS(결과를 모르는 결제)로 남아 있으면 replayReply 가 침묵한다.
		// 여기서 조회를 한 번 더 해볼 수도 있지만 하지 않는다 — 해소는 복구 배치 한 곳에만 둔다.
		// 두 곳에서 해소하면 같은 행을 동시에 확정하려 들고, 어느 쪽이 이겼는지 로그로 못 쫓는다.
		Payment existing = paymentRepository.findByOrderNo(orderNo).orElse(null);
		if (existing != null) {
			log.info("payment already exists. orderNo={} status={}", orderNo, existing.getStatus());
			return paymentTransactionService.replayReply(existing, eventId);
		}

		// 우리 쪽 식별자. PG 에는 보내지 않는다.
		String paymentId = "PAY-" + UUID.randomUUID();
		PgChargeResult result = charge(orderNo, customerId, amount);

		// 돈이 안 빠진 게 확실한 실패다. DB 를 건드리지 않고 던져서 Kafka 재시도에 맡긴다.
		// 멱등 선점은 record() 안에서 일어나므로, 여기서 나가면 애초에 선점되지 않는다 —
		// 되돌릴 게 없다는 게 이 경로의 장점이다.
		if (result.isRetryable()) {
			log.info("pg charge retryable. orderNo={} code={}", orderNo, result.failureCode());
			throw new PgRetryableException(result.failureCode(), result.failureMessage());
		}

		return paymentTransactionService.record(paymentId, orderNo, amount, result, eventId);
	}

	/**
	 * 등록된 카드가 없으면 PG 를 부르지 않고 거절한다.
	 * 예외를 던지면 재시도만 반복하다 주문이 PENDING 에 굳는다 — 거절 회신이 나가야 order 가 재고를 푼다.
	 */
	private PgChargeResult charge(String orderNo, String customerId, BigDecimal amount) {
		BillingKey billingKey = billingKeyRepository.findById(customerId).orElse(null);
		if (billingKey == null) {
			log.info("no billing key. orderNo={} customerId={}", orderNo, customerId);
			return PgChargeResult.rejected(NO_BILLING_KEY, "등록된 결제 수단이 없습니다.");
		}
		return pgClient.charge(orderNo, billingKey.getBillingKey(), customerId, amount);
	}
}
