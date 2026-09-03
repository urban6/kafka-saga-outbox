package com.urban6.payment.application;

import com.urban6.payment.domain.Payment;
import com.urban6.payment.infra.client.PgClient;
import com.urban6.payment.infra.client.PgConfirmResult;
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

	private final PaymentRepository paymentRepository;
	private final PgClient pgClient;
	private final PaymentTransactionService paymentTransactionService;

	public ApprovePaymentService(PaymentRepository paymentRepository,
			PgClient pgClient,
			PaymentTransactionService paymentTransactionService) {
		this.paymentRepository = paymentRepository;
		this.pgClient = pgClient;
		this.paymentTransactionService = paymentTransactionService;
	}

	/** HTTP 진입. 사가와 무관하므로 회신을 내지 않는다. */
	public Payment approve(String orderNo, String paymentKey, BigDecimal amount) {
		return process(orderNo, paymentKey, amount, null);
	}

	/** Kafka 진입. 멱등 선점과 회신 적재까지 포함한다. */
	public Payment approve(UUID eventId, String orderNo, String paymentKey, BigDecimal amount) {
		return process(orderNo, paymentKey, amount, eventId);
	}

	/**
	 * @param paymentKey 결제창이 인증을 마치며 발급한 키. 브라우저 → order → 여기까지 아무도 해석하지 않고
	 *                   넘겨온 값이며, PG 는 이 키와 인증 때의 금액이 맞아야 승인한다
	 */
	private Payment process(String orderNo, String paymentKey, BigDecimal amount, UUID eventId) {
		// 이미 끝난 주문이면 PG 를 다시 부르지 않는다. PG 에도 멱등키가 있어 안전하긴 하지만
		// 굳이 왕복할 이유가 없다. 다만 회신은 다시 낸다 — 앞선 회신이 유실됐을 수 있다.
		Payment existing = paymentRepository.findByOrderNo(orderNo).orElse(null);
		if (existing != null) {
			log.info("payment already exists. orderNo={} status={}", orderNo, existing.getStatus());
			return paymentTransactionService.replayReply(existing, eventId);
		}

		// 우리 쪽 식별자. PG 에는 보내지 않는다 — PG 가 아는 건 결제창이 발급한 paymentKey 뿐이다.
		String paymentId = "PAY-" + UUID.randomUUID();
		PgConfirmResult result = pgClient.confirm(orderNo, paymentKey, amount);

		return paymentTransactionService.record(paymentId, orderNo, amount, result, eventId);
	}
}
