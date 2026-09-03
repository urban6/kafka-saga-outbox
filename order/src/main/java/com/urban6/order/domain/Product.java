package com.urban6.order.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 상품과 재고. 재고를 별도 서비스로 두지 않고 order 안에 뒀다.
 *
 * 재고 증감은 엔티티 메서드가 아니라 ProductRepository 의 조건부 UPDATE 로만 한다.
 * 그래서 version 컬럼도 없다 — 낙관적 락과 조건부 UPDATE 를 섞으면 어느 쪽이 동시성을
 * 막고 있는지 코드에서 안 보인다.
 */
@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    /** 비즈니스 키가 곧 PK 다. 서비스 경계를 넘는 값이라 BIGINT 대리키를 두지 않는다. */
    @Id
    @Column(name = "product_id", nullable = false, length = 64)
    private String productId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    /** 창고에 있는 총 수량. 재고 확정(주문 완료) 시점에 줄어든다. */
    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    /** 결제를 기다리는 동안 잡아둔 수량. 확정되면 total 에서 빠지고, 해제되면 그냥 풀린다. */
    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
