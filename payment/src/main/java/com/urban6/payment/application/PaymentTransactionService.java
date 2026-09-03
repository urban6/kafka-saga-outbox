package com.urban6.payment.application;

import com.urban6.payment.domain.Payment;
import com.urban6.payment.domain.PaymentStatus;
import com.urban6.payment.infra.client.PgChargeResult;
import com.urban6.payment.infra.messaging.EventEnvelope;
import com.urban6.payment.infra.messaging.EventType;
import com.urban6.payment.infra.messaging.IdempotencyGuard;
import com.urban6.payment.infra.messaging.OutboxWriter;
import com.urban6.payment.infra.messaging.PaymentReplyPayload;
import com.urban6.payment.infra.persistence.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 결제 결과를 DB 에 확정하는 트랜잭션 경계.
 * <p>
 * <b>{@link ApprovePaymentService} 와 별개 빈인 이유</b> — 같은 클래스 안에서 {@code @Transactional}
 * 메서드를 부르면 프록시를 타지 않아 트랜잭션이 안 걸린다. PG 호출은 트랜잭션 밖, 그 반영은 안이어야
 * 하므로 이 경계는 빈 사이의 호출일 수밖에 없다.
 * <p>
 * 트랜잭션 하나에 <b>멱등 선점 · 결제 확정 · 회신 적재</b> 가 함께 들어간다. 하나라도 밖으로 빼면
 * "결제는 됐는데 회신이 없다" 또는 "선점만 남아 영영 재처리되지 않는다" 가 생긴다.
 */
@Service
@RequiredArgsConstructor
public class PaymentTransactionService {

	private static final Logger log = LoggerFactory.getLogger(PaymentTransactionService.class);

	private final PaymentRepository paymentRepository;
	private final OutboxWriter outboxWriter;
	private final IdempotencyGuard idempotencyGuard;

	/** {@code consumed_message.consumer_group} 에 들어간다. 컨슈머 그룹과 반드시 같아야 하므로 같은 프로퍼티를 읽는다. */
	@Value("${spring.kafka.consumer.group-id}")
	private final String consumerGroup;

	/**
	 * 새 결제를 확정한다.
	 *
	 * @param eventId Kafka 진입이면 커맨드 식별자, HTTP 진입이면 {@code null}
	 * @return 저장된 결제. 중복 메시지라 아무것도 하지 않았으면 {@code null}
	 */
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
			// 돈이 빠졌는지 모른다. 답을 미루고 행만 남긴다 — 복구 배치가 조회로 해소한다.
			case IN_DOUBT -> Payment.inDoubt(paymentId, orderNo, amount,
					result.failureCode(), result.failureMessage());
			// 여기 오면 안 된다. 재시도는 DB 에 아무것도 쓰지 않고 ApprovePaymentService 가 되돌린다 —
			// 행을 남기면 uk_order_no 때문에 다음 재시도가 영영 막힌다.
			case RETRYABLE -> throw new IllegalStateException(
					"retryable result must not be recorded. orderNo=" + orderNo
							+ " code=" + result.failureCode());
		};

		// uk_order_no 위반은 잡지 않고 던진다. 제약 위반이 난 트랜잭션은 이어서 쓸 수 없고,
		// 롤백시키면 멱등 선점도 함께 풀려 Kafka 재시도가 정상 경로로 흡수한다.
		Payment saved = paymentRepository.save(payment);
		appendReply(saved, eventId);

		log.info("payment recorded. orderNo={} status={}", orderNo, saved.getStatus());
		return saved;
	}

	/**
	 * 이미 처리된 결제에 대해 <b>회신만 다시 낸다.</b> 앞선 회신이 유실됐을 수 있어서다.
	 * 중복 회신은 order 쪽 방어선이 무시하지만, 안 보내면 order 가 영원히 대기한다.
	 */
	@Transactional
	public Payment replayReply(Payment existing, UUID eventId) {
		if (!claim(eventId, existing.getOrderNo())) {
			return existing;
		}
		appendReply(existing, eventId);
		return existing;
	}

	/**
	 * 결과를 모르던 결제를 확정한다. <b>복구 배치 전용.</b>
	 * <p>
	 * {@link #record} 와 다른 점은 둘이다 — 새 행을 만드는 게 아니라 있는 행을 옮기고,
	 * {@code eventId} 없이도 회신을 낸다. 애초에 회신을 미뤄둔 결제라 지금 내지 않으면 아무도 안 낸다.
	 *
	 * @return 이 호출이 확정했으면 {@code true}. 이미 누가 확정했으면 {@code false}
	 */
	@Transactional
	public boolean settle(Payment payment, PgChargeResult result) {
		String orderNo = payment.getOrderNo();
		Instant now = Instant.now();

		int moved = result.isApproved()
				? paymentRepository.settleApproved(payment.getPaymentId(), result.paymentKey(), now)
				: paymentRepository.settleRejected(payment.getPaymentId(),
						result.failureCode(), result.failureMessage(), now);

		if (moved == 0) {
			// 조건부 UPDATE 가 막았다. 회신도 이미 그쪽이 냈으므로 여기서 또 내면 중복이다.
			log.info("payment already settled elsewhere. orderNo={}", orderNo);
			return false;
		}

		// 회신은 엔티티가 아니라 PG 응답으로 만든다. 벌크 UPDATE 는 1차 캐시를 우회하므로
		// 지금 payment 객체는 여전히 IN_PROGRESS 인 옛 값이다.
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

	/** {@code eventId} 가 없으면(HTTP 진입) 멱등 판정 자체가 필요 없다. */
	private boolean claim(UUID eventId, String orderNo) {
		if (eventId == null) {
			return true;
		}
		boolean claimed = idempotencyGuard.claim(eventId, consumerGroup, "APPROVE_PAYMENT");
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
			// 결과를 모르는 동안은 회신하지 않는다. 승인 회신은 재고를 확정하고 거절 회신은 재고를 푸는데,
			// 어느 쪽도 틀리면 되돌릴 수 없다. 침묵하면 order 는 PENDING 에 머물고
			// 임계값을 넘는 순간 Stuck 탐지에 걸린다 — 조용히 사라지지 않는다.
			log.info("payment in doubt, reply deferred. orderNo={} failureCode={}",
					orderNo, payment.getFailureCode());
			return;
		}

		EventEnvelope<PaymentReplyPayload> envelope = switch (payment.getStatus()) {
			case DONE -> EventEnvelope.of(EventType.PAYMENT_APPROVED, orderNo,
					PaymentReplyPayload.approved(orderNo, payment.getPaymentKey()));
			case ABORTED -> EventEnvelope.of(EventType.PAYMENT_REJECTED, orderNo,
					PaymentReplyPayload.rejected(orderNo, payment.getFailureCode(), payment.getFailureReason()));
			// 나머지 상태는 아직 도달하지 않는다. 틀린 회신으로 사가를 잘못 미느니 침묵이 안전하다.
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
