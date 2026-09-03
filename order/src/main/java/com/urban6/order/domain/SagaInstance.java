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
 * 주문 사가의 진행 기록. 주문 하나당 정확히 하나다(uk_order_no).
 *
 * 이 행이 있어야 회신을 받았을 때 "내가 무엇을 기다리고 있었는지"를 알 수 있다.
 * 주문 생성과 같은 트랜잭션에서 INSERT 되므로 "커맨드는 나갔는데 기다리는 주체가 없다"가
 * 구조적으로 불가능하다.
 *
 * 상태 전이 메서드를 두지 않았다. 전이는 오케스트레이터가 리포지토리의 조건부 UPDATE 로만 한다 —
 * 엔티티에 complete() 같은 걸 두면 "조회 → 검사 → 저장"이 되어 중복 회신이 둘 다 통과한다.
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

	/**
	 * 보상에 필요한 컨텍스트를 누적한다. 통째로 갈아끼우면 안 된다 —
	 * 프로세스가 죽었다 살아나면 여기 남은 것만으로 보상해야 한다.
	 *
	 * Hibernate 7 이 Jackson 3 로 직렬화하므로 별도 컨버터가 필요 없다.
	 */
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

	/**
	 * saga_id 를 코드에서 직접 할당하므로 Spring Data 가 "새 엔티티"인지 판단할 근거가 없다.
	 * 그냥 두면 save() 가 merge() 로 가서 INSERT 앞에 불필요한 SELECT 를 한 번 더 한다.
	 *
	 * DB 에 없는 컬럼이라 @Transient 이며, 반드시 jakarta.persistence.Transient 여야 한다.
	 */
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
	 *
	 * payload 에는 커맨드를 다시 만들 수 있는 것(customerId, amount)을 남긴다.
	 * 재시작 후 재발행은 여기 남은 것으로 해야 한다. paymentKey 는 시작 시점엔 없다.
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
