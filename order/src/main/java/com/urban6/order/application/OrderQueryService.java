package com.urban6.order.application;

import com.urban6.order.api.dto.OrderTraceResponse;
import com.urban6.order.domain.Order;
import com.urban6.order.domain.SagaInstance;
import com.urban6.order.infra.persistence.OrderRepository;
import com.urban6.order.infra.persistence.SagaInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/** 주문 조회 유스케이스. 읽기 전용이라 쓰기 경로와 트랜잭션을 섞지 않는다. */
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final SagaInstanceRepository sagaInstanceRepository;

    /**
     * 주문과 사가를 한 스냅샷으로 읽어 DTO 까지 만든다. 트랜잭션이 두 가지를 한다 —
     * LAZY 인 items 를 읽을 경계를 만들고, 둘을 따로 읽었을 때 생기는
     * "orders=PENDING 인데 saga=COMPLETED" 같은 실재한 적 없는 조합을 막는다.
     */
    @Transactional(readOnly = true)
    public Optional<OrderTraceResponse> findByOrderNo(String orderNo) {
        Order order = orderRepository.findByOrderNo(orderNo).orElse(null);
        if (order == null) {
            return Optional.empty();
        }

        SagaInstance saga = sagaInstanceRepository.findByOrderNo(orderNo).orElse(null);
        return Optional.of(OrderTraceResponse.from(order, saga, Instant.now()));
    }
}
