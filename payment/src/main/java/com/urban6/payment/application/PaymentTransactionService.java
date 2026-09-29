package com.urban6.payment.application;

import com.urban6.payment.domain.Payment;
import com.urban6.payment.domain.PaymentStatus;
import com.urban6.payment.infra.client.PgChargeResult;
import com.urban6.payment.infra.messaging.CommandType;
import com.urban6.payment.infra.messaging.EventEnvelope;
import com.urban6.payment.infra.messaging.EventType;
import com.urban6.payment.infra.messaging.IdempotencyGuard;
import com.urban6.payment.infra.messaging.OutboxWriter;
import com.urban6.payment.infra.messaging.PaymentReplyPayload;
import com.urban6.payment.infra.persistence.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 멱등 선점 · 결제 확정 · 회신 적재를 한 트랜잭션으로 묶는다.
 * ApprovePaymentService 와 별개 빈이어야 한다 — 자기 호출은 프록시를 안 타 트랜잭션이 안 걸린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTransactionService {

	private final PaymentRepository paymentRepository;
	private final OutboxWriter outboxWriter;
	private final IdempotencyGuard idempotencyGuard;

	@Value("${spring.kafka.consumer.group-id}")
	private final String consumerGroup;

	/** @return 중복 메시지라 아무것도 하지 않았으면 null. eventId 는 HTTP 진입이면 null */
	@Transactional
	public Payment record(String paymentId, String orderNo, BigDecimal amount,
			PgChargeResult result, UUID eventId) {

		if (!claim(eventId, orderNo)) {
			return null;
		}

		Payment payment = switch (result.outcome()) {
			case APPROVED -> Payment.approved(paymentId, orderNo, amount, result.paymentKey());
			case REJECTED -> Payment.rejected(paymentId, orderNo, amount,
					result.failureCode(), result.failureMessage());
			case IN_DOUBT -> Payment.inDoubt(paymentId, orderNo, amount,
					result.failureCode(), result.failureMessage());
			// 행을 남기면 uk_order_no 때문에 다음 재시도가 영영 막힌다.
			case RETRYABLE -> throw new IllegalStateException(
					"retryable result must not be recorded. orderNo=" + orderNo
							+ " code=" + result.failureCode());
		};

		// uk_order_no 위반은 잡지 않는다. 롤백돼야 멱등 선점도 풀려 Kafka 재시도가 흡수한다.
		Payment saved = paymentRepository.save(payment);
		appendReply(saved, eventId);

		log.info("payment recorded. orderNo={} status={}", orderNo, saved.getStatus());
		return saved;
	}

	/** 앞선 회신이 유실됐을 수 있어 회신만 다시 낸다. 중복은 order 방어선이 무시한다. */
	@Transactional
	public Payment replayReply(Payment existing, UUID eventId) {
		if (!claim(eventId, existing.getOrderNo())) {
			return existing;
		}
		appendReply(existing, eventId);
		return existing;
	}

	/** in-doubt 복구 전용. @return 이미 누가 확정했으면 false */
	@Transactional
	public boolean settle(Payment payment, PgChargeResult result) {
		String orderNo = payment.getOrderNo();
		Instant now = Instant.now();

		int moved = result.isApproved()
				? paymentRepository.settleApproved(payment.getPaymentId(), result.paymentKey(), now)
				: paymentRepository.settleRejected(payment.getPaymentId(),
						result.failureCode(), result.failureMessage(), now);

		if (moved == 0) {
			// 이미 확정한 쪽이 회신도 냈다.
			log.info("payment already settled elsewhere. orderNo={}", orderNo);
			return false;
		}

		// 회신은 PG 응답으로 만든다. 벌크 UPDATE 가 1차 캐시를 우회해 payment 는 옛 값이다.
		EventEnvelope<PaymentReplyPayload> envelope = result.isApproved()
				? EventEnvelope.of(EventType.PAYMENT_APPROVED, orderNo,
						PaymentReplyPayload.approved(orderNo, result.paymentKey()))
				: EventEnvelope.of(EventType.PAYMENT_REJECTED, orderNo,
						PaymentReplyPayload.rejected(orderNo, result.failureCode(), result.failureMessage()));

		outboxWriter.append("Payment", envelope);
		log.info("in-doubt settled. orderNo={} outcome={} eventType={}",
				orderNo, result.outcome(), envelope.eventType());
		return true;
	}

	private boolean claim(UUID eventId, String orderNo) {
		if (eventId == null) {
			return true;
		}
		boolean claimed = idempotencyGuard.claim(
				eventId, consumerGroup, CommandType.APPROVE_PAYMENT.name());
		if (!claimed) {
			log.debug("duplicate command ignored. eventId={} orderNo={}", eventId, orderNo);
		}
		return claimed;
	}

	private void appendReply(Payment payment, UUID eventId) {
		if (eventId == null) {
			return;
		}
		String orderNo = payment.getOrderNo();

		if (payment.getStatus() == PaymentStatus.IN_PROGRESS) {
			// 틀린 회신은 되돌릴 수 없어 침묵한다. order 는 PENDING 에 남아 Stuck 탐지에 걸린다.
			log.info("payment in doubt, reply deferred. orderNo={} failureCode={}",
					orderNo, payment.getFailureCode());
			return;
		}

		EventEnvelope<PaymentReplyPayload> envelope = switch (payment.getStatus()) {
			case DONE -> EventEnvelope.of(EventType.PAYMENT_APPROVED, orderNo,
					PaymentReplyPayload.approved(orderNo, payment.getPaymentKey()));
			case ABORTED -> EventEnvelope.of(EventType.PAYMENT_REJECTED, orderNo,
					PaymentReplyPayload.rejected(orderNo, payment.getFailureCode(), payment.getFailureReason()));
			default -> null;
		};

		if (envelope == null) {
			log.warn("no reply for status. orderNo={} status={}", orderNo, payment.getStatus());
			return;
		}

		outboxWriter.append("Payment", envelope);
		log.info("reply queued. orderNo={} eventType={}", orderNo, envelope.eventType());
	}
}
