package com.urban6.payment.application;

import com.urban6.payment.domain.Payment;
import com.urban6.payment.infra.client.PgConfirmResult;
import com.urban6.payment.infra.messaging.EventEnvelope;
import com.urban6.payment.infra.messaging.EventType;
import com.urban6.payment.infra.messaging.IdempotencyGuard;
import com.urban6.payment.infra.messaging.OutboxWriter;
import com.urban6.payment.infra.messaging.PaymentReplyPayload;
import com.urban6.payment.infra.persistence.PaymentRepository;
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
 * <b>{@link ApprovePaymentService} 와 별개의 빈인 이유가 여기에 있다.</b>
 * 같은 클래스 안에서 {@code @Transactional} 메서드를 호출하면 스프링 프록시를 타지 않아
 * 트랜잭션이 걸리지 않는다. PG 호출은 트랜잭션 밖이어야 하고 그 결과 반영은 트랜잭션 안이어야 하므로,
 * 경계를 넘는 호출이 반드시 빈 사이의 호출이어야 한다.
 * <p>
 * 이 트랜잭션 안에 셋이 함께 들어간다 — <b>멱등 선점 · 결제 확정 · 회신 적재.</b>
 * 하나라도 밖으로 나가면 "결제는 됐는데 회신이 안 나갔다" 또는 "회신은 나갔는데 결제 기록이 없다"가
 * 생긴다. 특히 멱등 선점을 분리하면 처리 중 롤백됐을 때 선점만 남아 영영 재처리되지 않는다.
 */
@Service
public class PaymentTransactionService {

	private static final Logger log = LoggerFactory.getLogger(PaymentTransactionService.class);

	private final PaymentRepository paymentRepository;
	private final OutboxWriter outboxWriter;
	private final IdempotencyGuard idempotencyGuard;

	/** {@code consumed_message.consumer_group} 에 들어간다. 컨슈머 그룹과 반드시 같아야 하므로 같은 프로퍼티를 읽는다. */
	private final String consumerGroup;

	public PaymentTransactionService(PaymentRepository paymentRepository,
			OutboxWriter outboxWriter,
			IdempotencyGuard idempotencyGuard,
			@Value("${spring.kafka.consumer.group-id}") String consumerGroup) {
		this.paymentRepository = paymentRepository;
		this.outboxWriter = outboxWriter;
		this.idempotencyGuard = idempotencyGuard;
		this.consumerGroup = consumerGroup;
	}

	/**
	 * 새 결제를 확정한다.
	 *
	 * @param eventId 커맨드 메시지의 식별자. {@code null} 이면 HTTP 로 들어온 것이라
	 *                멱등 선점도 회신 적재도 하지 않는다
	 * @return 저장된 결제. 중복 메시지라 아무것도 하지 않았으면 {@code null}
	 */
	@Transactional
	public Payment record(String paymentId, String orderNo, BigDecimal amount,
			PgConfirmResult result, UUID eventId) {

		if (!claim(eventId, orderNo)) {
			return null;
		}

		Payment payment = result.isApproved()
				? Payment.approved(paymentId, orderNo, amount, result.paymentKey())
				: Payment.rejected(paymentId, orderNo, amount, result.failureCode(), result.failureMessage());

		// uk_order_no 위반은 여기서 잡지 않는다. 잡아서 재조회하려 해도 제약 위반이 난 트랜잭션은
		// 이미 롤백 대상이라 이어서 쓸 수 없다. 그대로 던져 전부 롤백시키면 멱등 선점도 함께 풀리고,
		// Kafka 가 재시도할 때는 기존 결제가 보이므로 정상 경로로 흡수된다.
		Payment saved = paymentRepository.save(payment);
		appendReply(saved, eventId);

		log.info("payment recorded. orderNo={} status={}", orderNo, saved.getStatus());
		return saved;
	}

	/**
	 * 이미 처리된 결제에 대해 <b>회신만 다시 낸다.</b>
	 * <p>
	 * 앞선 시도의 회신이 order 에 닿지 않았을 수 있어서다. 닿았다면 order 쪽 방어선이 무시하지만,
	 * 안 보내면 order 가 영원히 대기한다. 보내서 손해 보는 쪽이 훨씬 싸다.
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
			// 나머지 상태는 아직 도달하지 않는다. 회신 없이 두면 order 가 대기하고,
			// 잘못된 회신을 내면 사가가 틀린 방향으로 간다. 침묵이 안전한 쪽이다.
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
