package com.urban6.order.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 상태 전이 메서드를 두지 않는다. 조회→검사→저장이면 중복 회신이 둘 다 통과한다. 전이는 조건부 UPDATE 로만. */
@Entity
@Table(name = "saga_instance")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SagaInstance implements Persistable<UUID> {

	@Id
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "saga_id", nullable = false, length = 36)
	private UUID sagaId;

	@Column(name = "order_no", nullable = false, unique = true, length = 64)
	private String orderNo;

	@Enumerated(EnumType.STRING)
	@Column(name = "current_step", nullable = false, length = 64)
	private SagaStep currentStep;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private SagaStatus status;

	/** 보상 컨텍스트. 덮어쓰지 말고 누적한다 — 재시작 후엔 여기 남은 것만 쓸 수 있다. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private Map<String, Object> payload;

	/** Stuck 탐지 기준이라 단계가 바뀔 때만 갱신한다. */
	@Column(name = "step_started_at", nullable = false)
	private Instant stepStartedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	// PK 직접 할당이라 없으면 save() 가 merge() 로 가 SELECT 가 더 나간다. jakarta.persistence.Transient 여야 한다.
	@Transient
	private boolean isNew = true;

	private SagaInstance(String orderNo, String customerId, BigDecimal amount) {
		this.sagaId = UUID.randomUUID();
		this.orderNo = orderNo;
		this.currentStep = SagaStep.APPROVE_PAYMENT;
		this.status = SagaStatus.STARTED;
		this.payload = new LinkedHashMap<>();
		this.payload.put("customerId", customerId);
		this.payload.put("amount", amount);
		this.stepStartedAt = Instant.now();
	}

	public static SagaInstance start(String orderNo, String customerId, BigDecimal amount) {
		return new SagaInstance(orderNo, customerId, amount);
	}

	public boolean isTerminated() {
		return status.isTerminated();
	}

	@Override
	public UUID getId() {
		return sagaId;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

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
