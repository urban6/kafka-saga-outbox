package com.urban6.payment.application;

import com.urban6.payment.domain.Payment;
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

		Payment payment = result.isApproved()
				? Payment.approved(paymentId, orderNo, amount, result.paymentKey())
				: Payment.rejected(paymentId, orderNo, amount, result.failureCode(), result.failureMessage());

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
