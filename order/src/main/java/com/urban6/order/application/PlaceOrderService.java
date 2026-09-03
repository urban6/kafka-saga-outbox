package com.urban6.order.application;

import com.urban6.order.api.dto.PlaceOrderRequest;
import com.urban6.order.api.dto.PlaceOrderResponse;
import com.urban6.order.domain.Order;
import com.urban6.order.domain.OutOfStockException;
import com.urban6.order.domain.Product;
import com.urban6.order.domain.SagaInstance;
import com.urban6.order.infra.messaging.ApprovePaymentCommand;
import com.urban6.order.infra.messaging.EventEnvelope;
import com.urban6.order.infra.messaging.EventType;
import com.urban6.order.infra.messaging.OutboxWriter;
import com.urban6.order.infra.persistence.OrderRepository;
import com.urban6.order.infra.persistence.ProductRepository;
import com.urban6.order.infra.persistence.SagaInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 주문 접수 유스케이스. 주문 INSERT → 재고 예약 → 사가 INSERT → 결제 승인 커맨드 Outbox 적재를
 * <b>한 로컬 트랜잭션</b>으로 처리한다. 커밋되면 넷이 binlog 에 함께 실리고 Debezium 이 커맨드를 발행한다.
 * <p>
 * 원터치(빌링키) 결제라 주문 생성이 곧 사가의 시작이다. 결제창에서 사용자 인증을 기다릴 일이 없고,
 * 사용자는 202 를 받은 뒤 주문 조회를 폴링해 결과를 본다.
 */
@Service
public class PlaceOrderService {

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderService.class);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final SagaInstanceRepository sagaInstanceRepository;
    private final OutboxWriter outboxWriter;
    private final OrderNoGenerator orderNoGenerator;

    public PlaceOrderService(OrderRepository orderRepository,
                             ProductRepository productRepository,
                             SagaInstanceRepository sagaInstanceRepository,
                             OutboxWriter outboxWriter,
                             OrderNoGenerator orderNoGenerator) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.outboxWriter = outboxWriter;
        this.orderNoGenerator = orderNoGenerator;
    }

    /**
     * 재고 예약은 여기서 한다. 결제 승인 회신이 돌아올 때 품절을 만나지 않게 하려는 것이고,
     * 거절되면 오케스트레이터가 같은 수량을 되돌린다.
     * <p>
     * 뒷 라인 예약이 실패하면 <b>예외를 던져 전부 롤백</b>한다. 주문도 앞 라인 예약도 사가도 함께 사라진다.
     * <p>
     * 이 API 자체의 멱등 장치는 아직 없다. 더블탭이면 주문 둘 + 청구 둘이다 — {@code Idempotency-Key} 가 다음 스텝이다.
     */
    @Transactional
    public PlaceOrderResponse place(PlaceOrderRequest request) {
        Map<String, Integer> quantityByProduct = aggregateQuantities(request);
        Map<String, Product> products = loadProducts(quantityByProduct.keySet());

        String orderNo = orderNoGenerator.generate();
        Order order = Order.create(orderNo, request.customerId());
        for (PlaceOrderRequest.Item item : request.items()) {
            order.addItem(item.productId(), item.quantity(), products.get(item.productId()).getPrice());
        }

        reserveStock(quantityByProduct);
        orderRepository.save(order);

        // 커맨드를 적재하기 전에 "무엇을 기다리는지"를 먼저 남긴다.
        SagaInstance saga = sagaInstanceRepository.save(
                SagaInstance.start(orderNo, request.customerId(), order.getTotalAmount()));

        // 카드 정보는 싣지 않는다. order 는 고객만 알고, 빌링키는 payment 가 customerId 로 찾는다.
        outboxWriter.append("Order", EventEnvelope.of(
                EventType.APPROVE_PAYMENT,
                orderNo,
                new ApprovePaymentCommand(orderNo, request.customerId(), order.getTotalAmount())
        ));

        log.info("order placed, payment requested. orderNo={} customerId={} totalAmount={} sagaId={}",
                orderNo, request.customerId(), order.getTotalAmount(), saga.getSagaId());
        return new PlaceOrderResponse(orderNo, order.getStatus(), order.getTotalAmount());
    }

    /** 같은 상품이 여러 라인으로 들어와도 재고 예약은 상품당 한 번만 하도록 수량을 합친다. */
    private Map<String, Integer> aggregateQuantities(PlaceOrderRequest request) {
        return request.items().stream().collect(Collectors.groupingBy(
                PlaceOrderRequest.Item::productId,
                LinkedHashMap::new,
                Collectors.summingInt(PlaceOrderRequest.Item::quantity)
        ));
    }

    /**
     * 단가 조회. 재고 예약(벌크 UPDATE) 보다 <b>반드시 먼저</b> 해야 한다.
     * 벌크 UPDATE 는 영속성 컨텍스트를 우회하므로, 뒤에 읽으면 1차 캐시의 옛 값이 나온다.
     */
    private Map<String, Product> loadProducts(Set<String> productIds) {
        Map<String, Product> products = productRepository.findAllByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(Product::getProductId, Function.identity()));

        for (String productId : productIds) {
            if (!products.containsKey(productId)) {
                throw new IllegalArgumentException("unknown product: " + productId);
            }
        }
        return products;
    }

    /**
     * 조건부 UPDATE 로 예약한다. 갱신 행 수가 0 이면 가용 수량이 모자란 것이다.
     * 조회해서 재고를 확인한 뒤 예약하는 게 아니라 검사와 예약이 한 문장이므로,
     * 동시 주문이 몰려도 초과 예약이 나올 수 없다.
     */
    private void reserveStock(Map<String, Integer> quantityByProduct) {
        Instant now = Instant.now();
        for (Map.Entry<String, Integer> entry : quantityByProduct.entrySet()) {
            int updated = productRepository.reserve(entry.getKey(), entry.getValue(), now);
            if (updated == 0) {
                throw new OutOfStockException(entry.getKey(), entry.getValue());
            }
        }
    }
}
