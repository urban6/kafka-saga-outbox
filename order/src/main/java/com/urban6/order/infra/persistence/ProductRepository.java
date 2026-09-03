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
 * 재고 증감은 전부 조건부 UPDATE 다. 반환된 갱신 행 수가 곧 판정 결과이고,
 * 0 이면 조건이 안 맞은 것이니 호출부가 그에 맞게 분기한다.
 *
 * flushAutomatically = true 는 벌크 연산 전에 영속성 컨텍스트를 먼저 밀어내라는 뜻이다.
 * 안 그러면 아직 flush 안 된 변경이 UPDATE 뒤에 덮어써서 순서가 뒤집힌다.
 * clearAutomatically 는 켜지 않는다 — 컨텍스트를 비우면 같은 트랜잭션에서 만들고 있던
 * Order 까지 detach 돼서 이후 변경이 유실된다. 대신 이 메서드들을 호출한 뒤에는
 * 같은 트랜잭션에서 Product 를 엔티티로 다시 읽지 않는다(읽으면 1차 캐시의 옛 값이 나온다).
 */
public interface ProductRepository extends JpaRepository<Product, String> {

    List<Product> findAllByProductIdIn(Collection<String> productIds);

    /**
     * 재고 예약. 가용 수량이 모자라면 0 을 반환한다.
     *
     * "조회해서 재고를 확인하고 예약한다" 가 아니라 확인과 예약이 한 문장이다.
     * 동시에 열 건이 들어와도 InnoDB 가 행을 직렬화하므로 초과 예약이 나올 수 없다.
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
     * 재고 확정. 잡아둔 수량을 실제로 창고에서 뺀다. 결제 승인 회신을 받은 뒤 호출한다.
     *
     * reservedQuantity >= :quantity 조건은 중복 확정으로 음수가 되는 걸 막는다.
     * 다만 이건 안전장치일 뿐이고, 중복 실행 자체는 사가 상태 전이의 조건부 UPDATE 가 막는다.
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
