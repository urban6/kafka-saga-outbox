package com.urban6.order.api;

import com.urban6.order.application.OrderQueryService;
import com.urban6.order.application.PlaceOrderService;
import com.urban6.order.api.dto.OrderTraceResponse;
import com.urban6.order.api.dto.PlaceOrderRequest;
import com.urban6.order.api.dto.PlaceOrderResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final PlaceOrderService placeOrderService;
    private final OrderQueryService orderQueryService;

    public OrderController(PlaceOrderService placeOrderService,
                           OrderQueryService orderQueryService) {
        this.placeOrderService = placeOrderService;
        this.orderQueryService = orderQueryService;
    }

    /** 주문 접수가 곧 사가 시작이다. 비동기 작업을 받아둔 것이므로 202 이고, 결과는 Location 의 조회 API 를 폴링해 본다. */
    @PostMapping
    public ResponseEntity<PlaceOrderResponse> placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        PlaceOrderResponse response = placeOrderService.place(request);
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/orders/" + response.orderNo()))
                .body(response);
    }

    @GetMapping("/{orderNo}")
    public ResponseEntity<OrderTraceResponse> findByOrderNo(@PathVariable String orderNo) {
        return orderQueryService.findByOrderNo(orderNo)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
