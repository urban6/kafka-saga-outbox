package com.urban6.order.application;

import com.urban6.order.infra.messaging.EventEnvelope;
import com.urban6.order.infra.messaging.EventType;
import com.urban6.order.infra.messaging.ReserveStockCommand;
import com.urban6.order.api.dto.PlaceOrderRequest;
import com.urban6.order.api.dto.PlaceOrderResponse;
import com.urban6.order.domain.Order;
import com.urban6.order.infra.persistence.OrderRepository;
import com.urban6.order.infra.messaging.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlaceOrderService {

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderService.class);

    private final OrderRepository orderRepository;
    private final OrderNoGenerator orderNoGenerator;
    private final ProductPriceProvider priceProvider;
    private final OutboxWriter outboxWriter;

    public PlaceOrderService(OrderRepository orderRepository,
                             OrderNoGenerator orderNoGenerator,
                             ProductPriceProvider priceProvider,
                             OutboxWriter outboxWriter) {
        this.orderRepository = orderRepository;
        this.orderNoGenerator = orderNoGenerator;
        this.priceProvider = priceProvider;
        this.outboxWriter = outboxWriter;
    }

    /**
     * 주문을 생성하고 Outbox에 이벤트를 기록한다.
     * 트랜잭션 내에서 주문 저장과 같은 트랜잭션. 커밋되면 binlog 에 둘 다 실리고, 롤백되면 둘 다 없다.
     */
    @Transactional
    public PlaceOrderResponse place(PlaceOrderRequest request) {
        String orderNo = orderNoGenerator.generate();

        Order order = Order.create(orderNo, request.customerId());
        for (PlaceOrderRequest.Item item : request.items()) {
            order.addItem(item.productId(), item.quantity(), priceProvider.priceOf(item.productId()));
        }

        orderRepository.save(order);

        outboxWriter.append("Order", EventEnvelope.of(
                EventType.RESERVE_STOCK,
                orderNo,
                new ReserveStockCommand(orderNo, request.items().stream()
                        .map(i -> new ReserveStockCommand.Line(i.productId(), i.quantity()))
                        .toList())
        ));

        log.info("order placed. orderNo={} customerId={} totalAmount={}", orderNo, request.customerId(), order.getTotalAmount());
        return new PlaceOrderResponse(orderNo, order.getStatus(), order.getTotalAmount());
    }
}
