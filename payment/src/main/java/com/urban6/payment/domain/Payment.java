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

/** 상태 전이 메서드를 두지 않는다 — "조회 → 검사 → 저장" 은 동시 응답 둘을 다 통과시킨다. 조건부 UPDATE 로만. */
@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment implements Persistable<String> {

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

	@Column(name = "payment_key", length = 128)
	private String paymentKey;

	@Column(name = "failure_code", length = 64)
	private String failureCode;

	@Column(name = "failure_reason", length = 255)
	private String failureReason;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	// PK 직접 할당이라 없으면 save() 가 merge() 로 가 SELECT 가 더 나간다. jakarta.persistence.Transient 여야 한다.
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

	/** failureCode 에는 거절 근거가 아니라 왜 모르게 됐는지(PG_TIMEOUT 등)가 들어간다. */
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

	/** @PostLoad 가 없으면 조회한 행을 다시 INSERT 하려 든다. */
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
