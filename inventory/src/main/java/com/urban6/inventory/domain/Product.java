package com.urban6.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 상품과 재고.
 * <p>
 * 예약 수량 증감은 이 엔티티의 메서드가 아니라 리포지토리의 <b>조건부 UPDATE</b> 로 한다.
 * 읽고-검사하고-쓰면 같은 상품에 동시 주문이 들어올 때 재고를 초과 예약할 수 있기 때문이다.
 * 그래서 이 클래스에는 조회용 게터만 둔다.
 */
@Entity
@Table(name = "product")
public class Product {

	@Id
	@Column(name = "product_id", nullable = false, length = 64)
	private String productId;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false, precision = 19, scale = 4)
	private BigDecimal price;

	/** 전체 보유 수량. 예약이 잡혀도 줄지 않는다. */
	@Column(name = "total_quantity", nullable = false)
	private int totalQuantity;

	/** 예약된 수량. 가용 재고는 {@code totalQuantity - reservedQuantity}. */
	@Column(name = "reserved_quantity", nullable = false)
	private int reservedQuantity;

	@Column(nullable = false)
	private long version;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Product() {
	}

	public String getProductId() {
		return productId;
	}

	public String getName() {
		return name;
	}

	public BigDecimal getPrice() {
		return price;
	}

	public int getTotalQuantity() {
		return totalQuantity;
	}

	public int getReservedQuantity() {
		return reservedQuantity;
	}

	public int getAvailableQuantity() {
		return totalQuantity - reservedQuantity;
	}
}
