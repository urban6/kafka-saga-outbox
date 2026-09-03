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
import com.urban6.order.infra.persistence.ApiIdempotencyStore;
import com.urban6.order.infra.persistence.OrderRepository;
import com.urban6.order.infra.persistence.ProductRepository;
import com.urban6.order.infra.persistence.SagaInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 주문 접수 유스케이스. 주문 INSERT → 재고 예약 → 사가 INSERT → 결제 승인 커맨드 Outbox 적재를
 * 한 로컬 트랜잭션으로 처리한다. 커밋되면 넷이 binlog 에 함께 실리고 Debezium 이 커맨드를 발행한다.
 *
 * 원터치(빌링키) 결제라 주문 생성이 곧 사가의 시작이다. 결제창에서 사용자 인증을 기다릴 일이 없고,
 * 사용자는 202 를 받은 뒤 주문 조회를 폴링해 결과를 본다.
 */
@Service
@RequiredArgsConstructor
public class PlaceOrderService {

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderService.class);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final SagaInstanceRepository sagaInstanceRepository;
    private final OutboxWriter outboxWriter;
    private final OrderNoGenerator orderNoGenerator;
    private final ApiIdempotencyStore apiIdempotencyStore;
    private final ObjectMapper objectMapper;

    /**
     * 재고 예약은 여기서 한다. 결제 승인 회신이 돌아올 때 품절을 만나지 않게 하려는 것이고,
     * 거절되면 오케스트레이터가 같은 수량을 되돌린다.
     *
     * 뒷 라인 예약이 실패하면 예외를 던져 전부 롤백한다. 주문도 앞 라인 예약도 사가도 함께 사라진다.
     *
     * 멱등 선점이 이 트랜잭션 안에 있어야 하는 이유가 둘이다. 동시 요청은 유니크 인덱스 락에 걸려
     * 앞 요청이 커밋될 때까지 기다렸다가 재생으로 빠지고, 주문이 롤백되면 선점도 함께 풀려 재시도가 가능하다.
     */
    @Transactional
    public PlaceOrderResponse place(String idempotencyKey, PlaceOrderRequest request) {
        // order_no 를 선점 행에 함께 넣어야 두 번째 요청이 그 값을 읽을 수 있다. 그래서 선점보다 먼저 만든다.
        String orderNo = orderNoGenerator.generate();
        if (!apiIdempotencyStore.claim(idempotencyKey, requestHash(request), orderNo)) {
            return replay(idempotencyKey, request);
        }

        Map<String, Integer> quantityByProduct = aggregateQuantities(request);
        Map<String, Product> products = loadProducts(quantityByProduct.keySet());

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

    /**
     * 이미 처리한 키다. 새 주문을 만들지 않고 같은 주문을 다시 돌려준다.
     *
     * 최초 응답을 통째로 저장해두지 않는 이유는, 이 API 의 응답이 어차피 폴링으로 갱신되는 스냅샷이라
     * 지금 상태를 담아주는 편이 더 쓸모 있어서다. orderNo 와 Location 은 그대로다.
     */
    private PlaceOrderResponse replay(String idempotencyKey, PlaceOrderRequest request) {
        ApiIdempotencyStore.Claimed claimed = apiIdempotencyStore.find(idempotencyKey)
                // claim 이 실패했으면 행이 있다. 없다면 그 사이 정리 배치가 지운 것이므로 재시도가 맞다.
                .orElseThrow(() -> new IllegalStateException("idempotency record vanished: " + idempotencyKey));

        if (!claimed.requestHash().equals(requestHash(request))) {
            throw new IdempotencyConflictException(idempotencyKey);
        }

        Order order = orderRepository.findByOrderNo(claimed.orderNo())
                .orElseThrow(() -> new IllegalStateException("order missing for claimed key: " + idempotencyKey));

        log.info("duplicate order request replayed. orderNo={} idempotencyKey={}",
                order.getOrderNo(), idempotencyKey);
        return new PlaceOrderResponse(order.getOrderNo(), order.getStatus(), order.getTotalAmount());
    }

    /**
     * 같은 키에 다른 주문이 실려 오는 걸 잡기 위한 요청 지문.
     * record 는 선언 순서대로 직렬화되므로 같은 요청이면 같은 문자열이 나온다.
     */
    private String requestHash(PlaceOrderRequest request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
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
     * 단가 조회. 재고 예약(벌크 UPDATE) 보다 반드시 먼저 해야 한다.
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
