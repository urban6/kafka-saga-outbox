package com.urban6.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.time.Instant;

/**
 * 고객이 등록한 카드의 빌링키. 고객당 하나이며 재등록은 덮어쓴다.
 * 카드번호는 여기 없다 — 남는 건 끝 4자리뿐이고, 그게 이 서비스의 PCI 경계다.
 */
@Entity
@Table(name = "billing_key")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillingKey implements Persistable<String> {

	/** orders.customer_id 와 같은 값. 커맨드로 넘어와 여기서 키를 찾는 조회 키다. */
	@Id
	@Column(name = "customer_id", nullable = false, length = 64)
	private String customerId;

	@Column(name = "billing_key", nullable = false, length = 128)
	private String billingKey;

	@Column(name = "card_last4", nullable = false, length = 4)
	private String cardLast4;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/** Payment 와 같은 이유. PK 를 직접 할당하므로 새 행인지 여기서 말해줘야 INSERT 앞에 SELECT 가 안 나간다. */
	@Transient
	private boolean isNew = true;

	private BillingKey(String customerId, String billingKey, String cardLast4) {
		this.customerId = customerId;
		this.billingKey = billingKey;
		this.cardLast4 = cardLast4;
	}

	public static BillingKey of(String customerId, String billingKey, String cardLast4) {
		return new BillingKey(customerId, billingKey, cardLast4);
	}

	/** 재등록. 조회해 온 행에 새 키를 얹는다. */
	public BillingKey replace(String billingKey, String cardLast4) {
		this.billingKey = billingKey;
		this.cardLast4 = cardLast4;
		return this;
	}

	@Override
	public String getId() {
		return customerId;
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
