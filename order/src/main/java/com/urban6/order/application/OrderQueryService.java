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

/**
 * 주문 조회 유스케이스.
 *
 * PlaceOrderService 와 나눈 이유는 트랜잭션 성격이 다르기 때문이다.
 * 이쪽은 읽기 전용이고 쓰기 경로와 섞이면 안 된다.
 */
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final SagaInstanceRepository sagaInstanceRepository;

    /**
     * 트랜잭션이 두 가지 일을 한다.
     *
     * 하나는 open-in-view: false 아래에서 LAZY 인 items 를 읽을 경계를 만드는 것.
     *
     * 다른 하나가 더 중요하다 — 주문과 사가를 같은 스냅샷에서 읽는다. 따로 읽으면 그 틈에
     * 오케스트레이터가 전이시켜 orders=PENDING 인데 saga=COMPLETED 같은,
     * 실재한 적 없는 조합이 응답에 실린다. 진단 도구가 거짓말하면 없느니만 못하다.
     *
     * DTO 매핑도 이 안에서 끝낸다. 엔티티를 밖으로 내보내면 컨트롤러에서 LAZY 를 건드려 터진다.
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
