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
 * 재고 증감은 이 엔티티의 메서드가 아니라 ProductRepository 의 조건부 UPDATE 로 한다.
 * 엔티티에 reserve() 를 두면 "조회 → 검사 → 저장" 이 되는데, 그 사이를 다른 트랜잭션이
 * 파고들면 재고가 음수가 된다. 막으려면 비관적 락이나 @Version 이 필요하고 둘 다
 * 한정판 동시 주문에서 대기·재시도 비용을 만든다. 조건부 UPDATE 는 InnoDB 행 잠금 위에서
 * 검사와 갱신이 한 문장에 끝나므로 그 틈 자체가 없다.
 *
 * 그래서 version 컬럼도 두지 않는다. 낙관적 락과 조건부 UPDATE 를 섞으면
 * 어느 쪽이 동시성을 막고 있는지 코드에서 안 보인다.
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
