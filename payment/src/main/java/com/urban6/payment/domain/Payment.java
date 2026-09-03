package com.urban6.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 결제 1건. 주문 하나당 하나이며 그 uk_order_no 가 이 서비스의 멱등 장치다.
 *
 * 상태 전이 메서드를 두지 않는다. 엔티티에 approve() 를 두면 "조회 → 검사 → 저장" 이 되어
 * 응답이 두 번 들어올 때 둘 다 통과한다. 전이는 리포지토리의 조건부 UPDATE 로만 한다.
 */
@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment implements Persistable<String> {

	/** 우리가 발급하는 내부 식별자. 고객에게 노출되지 않아 order_no 같은 형식을 갖추지 않는다. */
	@Id
	@Column(name = "payment_id", nullable = false, length = 64)
	private String paymentId;

	@Column(name = "order_no", nullable = false, unique = true, length = 64)
	private String orderNo;

	@Column(nullable = false, precision = 19, scale = 4)
	private BigDecimal amount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private PaymentStatus status;

	/**
	 * PG 가 응답으로 확인해준 결제 식별자. 보통은 청구 응답에서 오고,
	 * ALREADY_PROCESSED_PAYMENT 면 조회로 가져온 그때의 키가 들어온다.
	 */
	@Column(name = "payment_key", length = 128)
	private String paymentKey;

	/** Toss 에러 본문의 code. 재시도 대상이었는지 사후에 판정하려면 메시지가 아니라 이게 필요하다. */
	@Column(name = "failure_code", length = 64)
	private String failureCode;

	@Column(name = "failure_reason", length = 255)
	private String failureReason;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	// PK 직접 할당이라 이게 없으면 save() 가 merge() 로 가서 INSERT 앞에 SELECT 가 하나 더 나간다.
	// 반드시 jakarta.persistence.Transient 여야 한다.
	@Transient
	private boolean isNew = true;

	private Payment(String paymentId, String orderNo, BigDecimal amount, PaymentStatus status) {
		this.paymentId = paymentId;
		this.orderNo = orderNo;
		this.amount = amount;
		this.status = status;
	}

	public static Payment approved(String paymentId, String orderNo, BigDecimal amount, String paymentKey) {
		Payment payment = new Payment(paymentId, orderNo, amount, PaymentStatus.DONE);
		payment.paymentKey = paymentKey;
		return payment;
	}

	public static Payment rejected(String paymentId, String orderNo, BigDecimal amount,
			String failureCode, String failureReason) {
		Payment payment = new Payment(paymentId, orderNo, amount, PaymentStatus.ABORTED);
		payment.failureCode = failureCode;
		payment.failureReason = failureReason;
		return payment;
	}

	/**
	 * 돈이 빠졌는지 모르는 결제. 승인으로도 거절로도 저장할 수 없어 "아직 답하지 않는다" 를 남긴다.
	 * failureCode 에는 거절 근거가 아니라 왜 모르게 됐는지(PG_TIMEOUT 등)가 들어간다.
	 */
	public static Payment inDoubt(String paymentId, String orderNo, BigDecimal amount,
			String failureCode, String failureReason) {
		Payment payment = new Payment(paymentId, orderNo, amount, PaymentStatus.IN_PROGRESS);
		payment.failureCode = failureCode;
		payment.failureReason = failureReason;
		return payment;
	}

	@Override
	public String getId() {
		return paymentId;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	/** INSERT 직후와 DB 에서 읽어온 직후. @PostLoad 가 없으면 조회한 행을 다시 INSERT 하려 든다. */
	@PostPersist
	@PostLoad
	void markNotNew() {
		this.isNew = false;
	}

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		this.updatedAt = Instant.now();
	}
}
