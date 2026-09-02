package com.urban6.order.application;

import com.urban6.order.api.dto.PlaceOrderRequest;
import com.urban6.order.api.dto.PlaceOrderResponse;
import com.urban6.order.domain.Order;
import com.urban6.order.infra.persistence.OrderRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PlaceOrderService {

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderService.class);

    private final OrderRepository orderRepository;
    private final OrderNoGenerator orderNoGenerator;
    private final ProductPriceProvider priceProvider;

    public PlaceOrderService(OrderRepository orderRepository,
                             OrderNoGenerator orderNoGenerator,
                             ProductPriceProvider priceProvider) {
        this.orderRepository = orderRepository;
        this.orderNoGenerator = orderNoGenerator;
        this.priceProvider = priceProvider;
    }

    @Transactional
    public PlaceOrderResponse place(PlaceOrderRequest request) {
        String orderNo = orderNoGenerator.generate();

        Order order = Order.create(orderNo, request.customerId());
        for (PlaceOrderRequest.Item item : request.items()) {
            order.addItem(
                    item.productId(),
                    item.quantity(),
                    priceProvider.priceOf(item.productId())
            );
        }

        orderRepository.save(order);

        log.info("order placed. orderNo={} customerId={} totalAmount={}",
                orderNo, request.customerId(), order.getTotalAmount());

        return new PlaceOrderResponse(orderNo, order.getStatus(), order.getTotalAmount());
    }
}
