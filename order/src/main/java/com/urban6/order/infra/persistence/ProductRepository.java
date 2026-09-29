package com.urban6.order.infra.persistence;

import com.urban6.order.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** clearAutomatically 는 켜지 않는다. 같은 트랜잭션에서 만들던 Order 까지 detach 된다. */
public interface ProductRepository extends JpaRepository<Product, String> {

    List<Product> findAllByProductIdIn(Collection<String> productIds);

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

    /** reservedQuantity 조건은 음수 방지일 뿐, 중복 실행은 사가 전이가 막는다. */
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
