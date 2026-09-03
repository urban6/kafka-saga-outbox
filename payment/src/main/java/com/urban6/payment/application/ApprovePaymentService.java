package com.urban6.payment.application;

import com.urban6.payment.domain.BillingKey;
import com.urban6.payment.domain.Payment;
import com.urban6.payment.infra.client.PgChargeResult;
import com.urban6.payment.infra.client.PgClient;
import com.urban6.payment.infra.persistence.BillingKeyRepository;
import com.urban6.payment.infra.persistence.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 결제 승인 유스케이스. 진입 경로는 둘 — HTTP({@code POST /api/payments})와
 * Kafka({@code payment.commands}). 어느 쪽으로 들어와도 하는 일은 같고,
 * <b>회신을 낼지 말지만 다르다.</b>
 * <p>
 * <b>{@code @Transactional} 이 없다.</b> 이 메서드 안에서 PG 를 호출하기 때문이다.
 * 외부 I/O 가 트랜잭션 안에 들어오면 PG 가 느려질 때 DB 커넥션이 그만큼 잡혀 있는다.
 * DB 확정은 {@link PaymentTransactionService} 가 별도 트랜잭션으로 처리한다.
 */
@Service
public class ApprovePaymentService {

	private static final Logger log = LoggerFactory.getLogger(ApprovePaymentService.class);

	/** PG 를 부르기 전에 우리가 내는 거절 코드. Toss 코드와 섞여 {@code failure_code} 에 남는다. */
	static final String NO_BILLING_KEY = "NO_BILLING_KEY";

	private final PaymentRepository paymentRepository;
	private final BillingKeyRepository billingKeyRepository;
	private final PgClient pgClient;
	private final PaymentTransactionService paymentTransactionService;

	public ApprovePaymentService(PaymentRepository paymentRepository,
			BillingKeyRepository billingKeyRepository,
			PgClient pgClient,
			PaymentTransactionService paymentTransactionService) {
		this.paymentRepository = paymentRepository;
		this.billingKeyRepository = billingKeyRepository;
		this.pgClient = pgClient;
		this.paymentTransactionService = paymentTransactionService;
	}

	/** HTTP 진입. 사가와 무관하므로 회신을 내지 않는다. */
	public Payment approve(String orderNo, String customerId, BigDecimal amount) {
		return process(orderNo, customerId, amount, null);
	}

	/** Kafka 진입. 멱등 선점과 회신 적재까지 포함한다. */
	public Payment approve(UUID eventId, String orderNo, String customerId, BigDecimal amount) {
		return process(orderNo, customerId, amount, eventId);
	}

	/**
	 * @param customerId 빌링키를 찾는 키. order 는 카드 정보를 모르고 고객만 안다
	 */
	private Payment process(String orderNo, String customerId, BigDecimal amount, UUID eventId) {
		// 이미 끝난 주문이면 PG 를 다시 부르지 않는다. PG 에도 멱등키가 있어 안전하긴 하지만
		// 굳이 왕복할 이유가 없다. 다만 회신은 다시 낸다 — 앞선 회신이 유실됐을 수 있다.
		Payment existing = paymentRepository.findByOrderNo(orderNo).orElse(null);
		if (existing != null) {
			log.info("payment already exists. orderNo={} status={}", orderNo, existing.getStatus());
			return paymentTransactionService.replayReply(existing, eventId);
		}

		// 우리 쪽 식별자. PG 에는 보내지 않는다 — PG 가 아는 건 빌링키와 청구 뒤 발급한 paymentKey 뿐이다.
		String paymentId = "PAY-" + UUID.randomUUID();
		PgChargeResult result = charge(orderNo, customerId, amount);

		return paymentTransactionService.record(paymentId, orderNo, amount, result, eventId);
	}

	/**
	 * 등록된 카드가 없으면 PG 를 부르지 않고 거절한다. 거절 회신이 나가야 order 가 재고를 푼다 —
	 * 여기서 예외를 던지면 재시도만 반복하다 주문이 {@code PENDING} 에 굳는다.
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
