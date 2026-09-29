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

@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final SagaInstanceRepository sagaInstanceRepository;

    /** 한 트랜잭션이어야 LAZY items 를 읽고, 주문과 사가가 어긋난 조합을 안 본다. */
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
