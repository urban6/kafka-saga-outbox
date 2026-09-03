package com.urban6.order.application;

import com.urban6.order.api.dto.PlaceOrderRequest;
import com.urban6.order.api.dto.PlaceOrderResponse;
import com.urban6.order.domain.Order;
import com.urban6.order.domain.OutOfStockException;
import com.urban6.order.domain.Product;
import com.urban6.order.infra.persistence.OrderRepository;
import com.urban6.order.infra.persistence.ProductRepository;
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
    private final OrderNoGenerator orderNoGenerator;

    public PlaceOrderService(OrderRepository orderRepository,
                             ProductRepository productRepository,
                             OrderNoGenerator orderNoGenerator) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.orderNoGenerator = orderNoGenerator;
    }

    /**
     * 주문서 생성. 주문 INSERT 와 재고 예약을 하나의 로컬 트랜잭션으로 처리한다.
     * <p>
     * 여기서 사가를 시작하지 않는다. 결제창에서 사용자 인증이 끝나야 {@code paymentKey} 가 생기고,
     * 그걸 들고 오는 confirm 요청이 사가의 시작점이다. 이 주문은 그때까지 {@code PENDING} 으로
     * 기다리며, 돌아오지 않으면 만료 배치가 재고를 풀고 주문을 닫는다.
     * <p>
     * 재고 예약은 여기서 한다. 결제창에 들어간 사용자가 승인 시점에 품절을 만나지 않게 하려는 것이고,
     * 그 대가로 이탈한 주문의 재고를 시간 기준으로 풀어야 한다.
     * <p>
     * 뒷 라인 예약이 실패하면 <b>예외를 던져 전부 롤백</b>한다. 주문도 앞 라인 예약도 함께 사라진다.
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

        log.info("checkout created. orderNo={} customerId={} totalAmount={}",
                orderNo, request.customerId(), order.getTotalAmount());
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
