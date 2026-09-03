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

/**
 * 주문 사가의 진행 기록. 주문 하나당 하나이며(uk_order_no) 주문 생성과 같은 트랜잭션에서 INSERT 된다.
 *
 * 상태 전이 메서드를 두지 않는다. 엔티티에 complete() 를 두면 "조회 → 검사 → 저장" 이 되어
 * 중복 회신이 둘 다 통과한다. 전이는 오케스트레이터가 조건부 UPDATE 로만 한다.
 */
@Entity
@Table(name = "saga_instance")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SagaInstance implements Persistable<UUID> {

	@Id
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "saga_id", nullable = false, length = 36)
	private UUID sagaId;

	/** 사가를 주문에 묶는 비즈니스 키. 회신 메시지의 aggregateId 로 이 행을 찾는다. */
	@Column(name = "order_no", nullable = false, unique = true, length = 64)
	private String orderNo;

	@Enumerated(EnumType.STRING)
	@Column(name = "current_step", nullable = false, length = 64)
	private SagaStep currentStep;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private SagaStatus status;

	/** 보상 컨텍스트. 누적이지 갈아끼우기가 아니다 — 재시작 후엔 여기 남은 것만 쓸 수 있다. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private Map<String, Object> payload;

	/** 현재 단계에 진입한 시각. Stuck 탐지 기준이라 단계가 바뀔 때만 갱신한다. */
	@Column(name = "step_started_at", nullable = false)
	private Instant stepStartedAt;

	@Column(name = "retry_count", nullable = false)
	private int retryCount;

	@Column(name = "last_error")
	private String lastError;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	// PK 직접 할당이라 이게 없으면 save() 가 merge() 로 가서 INSERT 앞에 SELECT 가 하나 더 나간다.
	// 반드시 jakarta.persistence.Transient 여야 한다.
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
		this.retryCount = 0;
	}

	/**
	 * 주문 접수 트랜잭션에서 호출한다. 첫 단계는 언제나 결제 승인이다.
	 * payload 에는 커맨드를 다시 만들 수 있는 것(customerId, amount)만 남긴다.
	 */
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
