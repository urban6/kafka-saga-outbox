package com.urban6.order.infra.persistence;

import com.urban6.order.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 재고 증감은 전부 조건부 UPDATE 다. 갱신 행 수가 곧 판정이고 0 이면 조건이 안 맞은 것이다.
 *
 * clearAutomatically 는 켜지 않는다 — 컨텍스트를 비우면 같은 트랜잭션에서 만들고 있던
 * Order 까지 detach 된다. 대신 호출 뒤에는 Product 를 엔티티로 다시 읽지 않는다.
 */
public interface ProductRepository extends JpaRepository<Product, String> {

    List<Product> findAllByProductIdIn(Collection<String> productIds);

    /**
     * 재고 예약. 가용 수량이 모자라면 0 을 반환한다.
     * 확인과 예약이 한 문장이라 동시 주문이 몰려도 초과 예약이 나올 수 없다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Product p
               set p.reservedQuantity = p.reservedQuantity + :quantity,
                   p.updatedAt        = :now
             where p.productId = :productId
               and p.totalQuantity - p.reservedQuantity >= :quantity
            """)
    int reserve(@Param("productId") String productId,
                @Param("quantity") int quantity,
                @Param("now") Instant now);

    /**
     * 재고 확정. 잡아둔 수량을 창고에서 뺀다. 결제 승인 회신을 받은 뒤 호출한다.
     * reservedQuantity >= :quantity 는 음수 방지 안전장치일 뿐, 중복 실행은 사가 전이가 막는다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Product p
               set p.totalQuantity    = p.totalQuantity - :quantity,
                   p.reservedQuantity = p.reservedQuantity - :quantity,
                   p.updatedAt        = :now
             where p.productId = :productId
               and p.reservedQuantity >= :quantity
            """)
    int confirm(@Param("productId") String productId,
                @Param("quantity") int quantity,
                @Param("now") Instant now);

    /** 재고 해제(보상). 결제가 거절됐을 때 예약분을 되돌린다. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Product p
               set p.reservedQuantity = p.reservedQuantity - :quantity,
                   p.updatedAt        = :now
             where p.productId = :productId
               and p.reservedQuantity >= :quantity
            """)
    int release(@Param("productId") String productId,
                @Param("quantity") int quantity,
                @Param("now") Instant now);
}
