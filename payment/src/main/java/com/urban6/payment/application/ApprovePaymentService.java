package com.urban6.payment.application;

import com.urban6.payment.domain.BillingKey;
import com.urban6.payment.domain.Payment;
import com.urban6.payment.infra.client.PgChargeResult;
import com.urban6.payment.infra.client.PgClient;
import com.urban6.payment.infra.persistence.BillingKeyRepository;
import com.urban6.payment.infra.persistence.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 결제 승인 유스케이스. HTTP({@code POST /api/payments})와 Kafka({@code payment.commands}) 둘 다 여기로 온다.
 * <p>
 * <b>{@code @Transactional} 이 없다.</b> 안에서 PG 를 호출하기 때문이다 — 외부 I/O 가 트랜잭션에 들어오면
 * PG 가 느린 만큼 DB 커넥션이 잡혀 있는다. DB 확정은 {@link PaymentTransactionService} 가 맡는다.
 */
@Service
@RequiredArgsConstructor
public class ApprovePaymentService {

	private static final Logger log = LoggerFactory.getLogger(ApprovePaymentService.class);

	/** PG 를 부르기 전에 우리가 내는 거절 코드. Toss 코드와 섞여 {@code failure_code} 에 남는다. */
	static final String NO_BILLING_KEY = "NO_BILLING_KEY";

	private final PaymentRepository paymentRepository;
	private final BillingKeyRepository billingKeyRepository;
	private final PgClient pgClient;
	private final PaymentTransactionService paymentTransactionService;

	/** HTTP 진입. 사가와 무관하므로 회신을 내지 않는다. */
	public Payment approve(String orderNo, String customerId, BigDecimal amount) {
		return process(orderNo, customerId, amount, null);
	}

	/** Kafka 진입. {@code eventId} 가 있으면 멱등 선점과 회신 적재까지 한다. */
	public Payment approve(UUID eventId, String orderNo, String customerId, BigDecimal amount) {
		return process(orderNo, customerId, amount, eventId);
	}

	private Payment process(String orderNo, String customerId, BigDecimal amount, UUID eventId) {
		// 이미 처리된 주문이면 PG 를 다시 부르지 않는다. 다만 회신은 다시 낸다 — 앞선 회신이 유실됐을 수 있다.
		Payment existing = paymentRepository.findByOrderNo(orderNo).orElse(null);
		if (existing != null) {
			log.info("payment already exists. orderNo={} status={}", orderNo, existing.getStatus());
			return paymentTransactionService.replayReply(existing, eventId);
		}

		// 우리 쪽 식별자. PG 에는 보내지 않는다.
		String paymentId = "PAY-" + UUID.randomUUID();
		PgChargeResult result = charge(orderNo, customerId, amount);

		return paymentTransactionService.record(paymentId, orderNo, amount, result, eventId);
	}

	/**
	 * 등록된 카드가 없으면 PG 를 부르지 않고 거절한다.
	 * 예외를 던지면 재시도만 반복하다 주문이 {@code PENDING} 에 굳는다 — 거절 회신이 나가야 order 가 재고를 푼다.
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
