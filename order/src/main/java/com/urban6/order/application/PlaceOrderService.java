package com.urban6.order.application;

import com.urban6.order.api.dto.PlaceOrderResponse;
import com.urban6.order.application.exception.IdempotencyConflictException;
import com.urban6.order.domain.Order;
import com.urban6.order.domain.OrderNoGenerator;
import com.urban6.order.domain.Product;
import com.urban6.order.domain.SagaInstance;
import com.urban6.order.domain.exception.OutOfStockException;
import com.urban6.order.domain.exception.UnknownProductException;
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
    private final ApiIdempotencyStore apiIdempotencyStore;
    private final ObjectMapper objectMapper;

    /** 멱등 선점부터 커맨드 적재까지 한 트랜잭션이다. */
    @Transactional
    public PlaceOrderResponse place(String idempotencyKey, PlaceOrderCommand command) {
        String orderNo = OrderNoGenerator.generate();
        if (!apiIdempotencyStore.claim(idempotencyKey, requestHash(command), orderNo)) {
            return replay(idempotencyKey, command);
        }

        SortedMap<String, Integer> quantityByProduct = quantitiesByProduct(command);
        Map<String, Product> products = loadProducts(quantityByProduct.keySet());

        Order order = Order.create(orderNo, command.customerId());
        for (PlaceOrderCommand.Item item : command.items()) {
            order.addItem(item.productId(), item.quantity(), products.get(item.productId()).getPrice());
        }

        reserveStock(quantityByProduct);
        orderRepository.save(order);

        SagaInstance saga = sagaInstanceRepository.save(
                SagaInstance.start(orderNo, command.customerId(), order.getTotalAmount()));

        // 카드 정보는 싣지 않는다. 빌링키는 payment 가 customerId 로 찾는다.
        outboxWriter.append("Order", EventEnvelope.of(
                EventType.APPROVE_PAYMENT,
                orderNo,
                new ApprovePaymentCommand(orderNo, command.customerId(), order.getTotalAmount())
        ));

        log.info("order placed, payment requested. orderNo={} customerId={} totalAmount={} sagaId={}",
                orderNo, command.customerId(), order.getTotalAmount(), saga.getSagaId());
        return new PlaceOrderResponse(orderNo, order.getStatus(), order.getTotalAmount());
    }

    /** 저장해둔 응답이 아니라 주문의 현재 상태를 돌려준다. */
    private PlaceOrderResponse replay(String idempotencyKey, PlaceOrderCommand command) {
        ApiIdempotencyStore.Claimed claimed = apiIdempotencyStore.find(idempotencyKey)
                // 그 사이 정리 배치가 지웠다면 재시도가 맞다.
                .orElseThrow(() -> new IllegalStateException("idempotency record vanished: " + idempotencyKey));

        if (!claimed.requestHash().equals(requestHash(command))) {
            throw new IdempotencyConflictException(idempotencyKey);
        }

        Order order = orderRepository.findByOrderNo(claimed.orderNo())
                .orElseThrow(() -> new IllegalStateException("order missing for claimed key: " + idempotencyKey));

        log.info("duplicate order request replayed. orderNo={} idempotencyKey={}",
                order.getOrderNo(), idempotencyKey);
        return new PlaceOrderResponse(order.getOrderNo(), order.getStatus(), order.getTotalAmount());
    }

    /** record 는 선언 순서대로 직렬화되므로 같은 요청이면 같은 지문이 나온다. */
    private String requestHash(PlaceOrderCommand command) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(command).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** TreeMap 정렬이 재고 락 획득 순서다. 확정·해제도 같은 정렬이어야 해서 SortedMap 으로 강제한다. */
    private SortedMap<String, Integer> quantitiesByProduct(PlaceOrderCommand command) {
        return command.items().stream().collect(Collectors.toMap(
                PlaceOrderCommand.Item::productId,
                PlaceOrderCommand.Item::quantity,
                (first, second) -> {
                    throw new IllegalStateException("duplicate productId passed validation");
                },
                TreeMap::new
        ));
    }

    /** 재고 예약(벌크 UPDATE)보다 먼저 읽어야 한다. 뒤에 읽으면 1차 캐시의 옛 값이 나온다. */
    private Map<String, Product> loadProducts(Set<String> productIds) {
        Map<String, Product> products = productRepository.findAllByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(Product::getProductId, Function.identity()));

        for (String productId : productIds) {
            if (!products.containsKey(productId)) {
                throw new UnknownProductException(productId);
            }
        }
        return products;
    }

    /** 검사와 예약이 한 문장이라 초과 예약이 나올 수 없다. 0건이면 재고 부족이다. */
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
