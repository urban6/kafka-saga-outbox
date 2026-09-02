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

@Service
public class PlaceOrderService {

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderService.class);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final SagaInstanceRepository sagaInstanceRepository;
    private final OrderNoGenerator orderNoGenerator;
    private final OutboxWriter outboxWriter;

    public PlaceOrderService(OrderRepository orderRepository,
                             ProductRepository productRepository,
                             SagaInstanceRepository sagaInstanceRepository,
                             OrderNoGenerator orderNoGenerator,
                             OutboxWriter outboxWriter) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.orderNoGenerator = orderNoGenerator;
        this.outboxWriter = outboxWriter;
    }

    /**
     * 주문 생성 · 재고 예약 · <b>사가 시작 기록</b> · 결제 요청 적재를 하나의 로컬 트랜잭션으로 처리한다.
     * <p>
     * 재고가 별도 서비스였을 때는 뒷 라인 예약이 실패해도 앞 라인이 이미 커밋돼 있어서
     * 상쇄 UPDATE 로 되돌려야 했다. 같은 DB 로 들어온 지금은 <b>예외를 던지면 전부 롤백</b>된다.
     * 주문도 예약도 outbox 행도 함께 사라지므로 사가가 시작되지 않는다.
     * <p>
     * 커밋되면 binlog 에 주문과 outbox 행이 함께 실리고, Debezium 이 그걸 읽어
     * {@code payment.commands} 로 내보낸다. 발행 여부를 애플리케이션은 알지 못한다.
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
        // 같은 트랜잭션이라 순서 자체가 중요한 건 아니지만, 읽는 사람에게 인과가 드러난다.
        SagaInstance saga = sagaInstanceRepository.save(SagaInstance.start(orderNo));

        outboxWriter.append("Order", EventEnvelope.of(
                EventType.APPROVE_PAYMENT,
                orderNo,
                new ApprovePaymentCommand(orderNo, order.getTotalAmount())
        ));

        log.info("order placed. orderNo={} sagaId={} customerId={} totalAmount={}",
                orderNo, saga.getSagaId(), request.customerId(), order.getTotalAmount());
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
