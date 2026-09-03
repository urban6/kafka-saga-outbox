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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceOrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final SagaInstanceRepository sagaInstanceRepository;
    private final OutboxWriter outboxWriter;
    private final OrderNoGenerator orderNoGenerator;
    private final ApiIdempotencyStore apiIdempotencyStore;
    private final ObjectMapper objectMapper;

    /**
     * 주문을 접수한다. 멱등 선점, 재고 예약, 주문·사가 저장, 결제 승인 커맨드 적재를
     * 한 트랜잭션으로 처리한다. 같은 Idempotency-Key 가 다시 오면 먼저 만든 주문을 돌려준다.
     */
    @Transactional
    public PlaceOrderResponse place(String idempotencyKey, PlaceOrderRequest request) {
        String orderNo = orderNoGenerator.generate();
        if (!apiIdempotencyStore.claim(idempotencyKey, requestHash(request), orderNo)) {
            return replay(idempotencyKey, request);
        }

        SortedMap<String, Integer> quantityByProduct = quantitiesByProduct(request);
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
     * 지금 상태를 담아주는 편이 더 쓸모 있어서다. 클라이언트가 붙잡고 있는 건 orderNo 이고, 그건 그대로다.
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

    /**
     * 상품별 수량. 검증이 중복 productId 를 이미 막았으므로 라인과 1:1 이다.
     *
     * TreeMap 의 정렬 순서가 곧 락 획득 순서다. 요청 순서대로 두면 상품을 반대로 담은
     * 동시 주문끼리 데드락이 난다. 확정·해제(Order.quantitiesByProduct())도 같은 정렬이어야 한다.
     * 반환 타입을 SortedMap 으로 좁힌 건 그 정렬을 주석이 아니라 컴파일러가 지키게 하려는 것이다.
     *
     * 병합 함수는 도달하지 않지만 던진다. 검증이 뚫렸는데 조용히 덮어쓰면 수량이 소리 없이 틀어진다.
     */
    private SortedMap<String, Integer> quantitiesByProduct(PlaceOrderRequest request) {
        return request.items().stream().collect(Collectors.toMap(
                PlaceOrderRequest.Item::productId,
                PlaceOrderRequest.Item::quantity,
                (first, second) -> {
                    throw new IllegalStateException("duplicate productId passed validation");
                },
                TreeMap::new
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
    private void reserveStock(SortedMap<String, Integer> quantityByProduct) {
        Instant now = Instant.now();
        for (Map.Entry<String, Integer> entry : quantityByProduct.entrySet()) {
            int updated = productRepository.reserve(entry.getKey(), entry.getValue(), now);
            if (updated == 0) {
                throw new OutOfStockException(entry.getKey(), entry.getValue());
            }
        }
    }
}
