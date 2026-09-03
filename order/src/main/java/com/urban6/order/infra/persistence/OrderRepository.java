package com.urban6.order.infra.persistence;

import com.urban6.order.domain.Order;
import com.urban6.order.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);

    /**
     * 주문 상태 전이. 기대한 상태가 아니면 0건이고, 호출부가 그에 맞게 분기한다.
     *
     * 엔티티를 로드해 바꾸지 않는 이유는 "조회 → 검사 → 저장" 사이에 다른 트랜잭션이 파고들 수
     * 있기 때문이다. 조건부 UPDATE 는 검사와 갱신이 한 문장이라 그 틈이 없다.
     *
     * Order 대신 FQN 을 쓴 건 HQL 파서가 order 를 정렬 키워드로 볼 여지를 없애려는 것이다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update com.urban6.order.domain.Order o
               set o.status    = :next,
                   o.updatedAt = :now
             where o.orderNo = :orderNo
               and o.status  = :expected
            """)
    int transitionStatus(@Param("orderNo") String orderNo,
                         @Param("expected") OrderStatus expected,
                         @Param("next") OrderStatus next,
                         @Param("now") Instant now);
}
