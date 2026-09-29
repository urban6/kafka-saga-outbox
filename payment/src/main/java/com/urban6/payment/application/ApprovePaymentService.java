package com.urban6.payment.application;

import com.urban6.payment.domain.BillingKey;
import com.urban6.payment.domain.Payment;
import com.urban6.payment.infra.client.PgChargeResult;
import com.urban6.payment.infra.client.PgClient;
import com.urban6.payment.infra.client.exception.PgRetryableException;
import com.urban6.payment.infra.persistence.BillingKeyRepository;
import com.urban6.payment.infra.persistence.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/** @Transactional 이 없다 — PG 호출은 트랜잭션 밖이고, DB 확정은 PaymentTransactionService 가 맡는다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovePaymentService {

	static final String NO_BILLING_KEY = "NO_BILLING_KEY";

	private final PaymentRepository paymentRepository;
	private final BillingKeyRepository billingKeyRepository;
	private final PgClient pgClient;
	private final PaymentTransactionService paymentTransactionService;

	/** HTTP 진입은 회신을 내지 않는다. */
	public Payment approve(String orderNo, String customerId, BigDecimal amount) {
		return process(orderNo, customerId, amount, null);
	}

	public Payment approve(UUID eventId, String orderNo, String customerId, BigDecimal amount) {
		return process(orderNo, customerId, amount, eventId);
	}

	private Payment process(String orderNo, String customerId, BigDecimal amount, UUID eventId) {
		// IN_PROGRESS 여도 여기서 조회하지 않는다. 해소를 두 곳에 두면 같은 행을 동시에 확정하려 든다.
		Payment existing = paymentRepository.findByOrderNo(orderNo).orElse(null);
		if (existing != null) {
			log.info("payment already exists. orderNo={} status={}", orderNo, existing.getStatus());
			return paymentTransactionService.replayReply(existing, eventId);
		}

		String paymentId = "PAY-" + UUID.randomUUID();
		PgChargeResult result = charge(orderNo, customerId, amount);

		// DB 를 건드리지 않고 던진다. 선점은 record() 안이라 여기서 나가면 되돌릴 게 없다.
		if (result.isRetryable()) {
			log.info("pg charge retryable. orderNo={} code={}", orderNo, result.failureCode());
			throw new PgRetryableException(result.failureCode(), result.failureMessage());
		}

		return paymentTransactionService.record(paymentId, orderNo, amount, result, eventId);
	}

	/** 카드가 없으면 예외가 아니라 거절이다 — 거절 회신이 나가야 order 가 재고를 푼다. */
	private PgChargeResult charge(String orderNo, String customerId, BigDecimal amount) {
		BillingKey billingKey = billingKeyRepository.findById(customerId).orElse(null);
		if (billingKey == null) {
			log.info("no billing key. orderNo={} customerId={}", orderNo, customerId);
			return PgChargeResult.rejected(NO_BILLING_KEY, "등록된 결제 수단이 없습니다.");
		}
		return pgClient.charge(orderNo, billingKey.getBillingKey(), customerId, amount);
	}
}
