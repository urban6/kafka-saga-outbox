package com.urban6.inventory.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 주문 한 건이 상품 하나에 잡아둔 예약.
 * <p>
 * {@code uk_order_product (order_no, product_id)} 가 멱등성의 2차 방어선이다. 멱등 테이블을
 * 어떤 이유로 통과한 중복 메시지가 와도 여기서 제약 위반으로 막힌다.
 */
@Entity
@Table(name = "stock_reservation")
public class StockReservation {

	@Id
	@Column(name = "reservation_id", nullable = false, length = 64)
	private String reservationId;

	/** 비즈니스 키. 서비스 경계를 넘는 값이라 {@code _no} 다. */
	@Column(name = "order_no", nullable = false, length = 64)
	private String orderNo;

	@Column(name = "product_id", nullable = false, length = 64)
	private String productId;

	@Column(nullable = false)
	private int quantity;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private ReservationStatus status;

	@Column(nullable = false)
	private long version;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected StockReservation() {
	}

	private StockReservation(String reservationId, String orderNo, String productId, int quantity) {
		this.reservationId = reservationId;
		this.orderNo = orderNo;
		this.productId = productId;
		this.quantity = quantity;
		this.status = ReservationStatus.RESERVED;
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	public static StockReservation reserve(String orderNo, String productId, int quantity) {
		return new StockReservation(UUID.randomUUID().toString(), orderNo, productId, quantity);
	}

	public String getReservationId() {
		return reservationId;
	}

	public String getOrderNo() {
		return orderNo;
	}

	public String getProductId() {
		return productId;
	}

	public int getQuantity() {
		return quantity;
	}

	public ReservationStatus getStatus() {
		return status;
	}
}
