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
 * 결제 1건. 주문 하나당 하나다({@code uk_order_no}).
 * <p>
 * <b>그 유니크 제약이 이 서비스의 멱등 장치다.</b> 같은 주문에 대한 승인 요청이 두 번 들어와도
 * 두 번째 INSERT 는 제약 위반으로 막힌다.
 * <p>
 * 상태 전이 메서드를 두지 않았다. 지금은 PG 응답을 받은 뒤 결과와 함께 INSERT 하므로
 * 전이 자체가 없다. 사가에 붙어 outbox 회신까지 한 트랜잭션으로 묶이면 그때 리포지토리의
 * 조건부 UPDATE 로 만든다 — 엔티티에 {@code approve()} 를 두면 "조회 → 검사 → 저장"이 되어
 * 응답이 두 번 들어올 때 둘 다 통과한다.
 */
@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment implements Persistable<String> {

	/**
	 * 우리가 발급하는 결제 식별자. PG 에는 보내지 않는 내부 값이다.
	 * <p>
	 * {@code order_no} 처럼 형식을 갖추지 않는 이유는 고객에게 노출되지 않기 때문이다.
	 */
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
	 * PG 가 응답으로 확인해준 결제 식별자. 결제창이 발급해 커맨드로 넘어온 키가 정상이면 그대로 돌아오지만,
	 * {@code ALREADY_PROCESSED_PAYMENT} 처럼 이전 시도가 성사된 경우엔 조회로 가져온 그때의 키가 들어온다.
	 * 저장하는 건 언제나 <b>PG 가 확인해준 값</b>이다.
	 */
	@Column(name = "payment_key", length = 128)
	private String paymentKey;

	/** Toss 에러 본문의 {@code code}. 재시도 대상이었는지 사후에 판정하려면 메시지가 아니라 이게 필요하다. */
	@Column(name = "failure_code", length = 64)
	private String failureCode;

	@Column(name = "failure_reason", length = 255)
	private String failureReason;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/**
	 * {@code payment_id} 를 코드에서 직접 할당하므로 Spring Data 가 "새 엔티티"인지 판단할 근거가 없다.
	 * 그냥 두면 {@code save()} 가 {@code merge()} 로 가서 INSERT 앞에 불필요한 SELECT 를 한 번 더 한다.
	 * <p>
	 * DB 에 없는 컬럼이라 {@code @Transient} 이며, 반드시 {@code jakarta.persistence.Transient} 여야 한다.
	 */
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

	@Override
	public String getId() {
		return paymentId;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	/** INSERT 직후와 DB 에서 읽어온 직후. {@code @PostLoad} 가 없으면 조회한 행을 다시 INSERT 하려 든다. */
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
