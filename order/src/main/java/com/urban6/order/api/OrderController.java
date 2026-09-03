package com.urban6.order.api;

import com.urban6.order.application.ConfirmOrderService;
import com.urban6.order.application.OrderQueryService;
import com.urban6.order.application.PlaceOrderService;
import com.urban6.order.api.dto.ConfirmOrderRequest;
import com.urban6.order.api.dto.ConfirmOrderResponse;
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
    private final ConfirmOrderService confirmOrderService;
    private final OrderQueryService orderQueryService;

    public OrderController(PlaceOrderService placeOrderService,
                           ConfirmOrderService confirmOrderService,
                           OrderQueryService orderQueryService) {
        this.placeOrderService = placeOrderService;
        this.confirmOrderService = confirmOrderService;
        this.orderQueryService = orderQueryService;
    }

    @PostMapping
    public ResponseEntity<PlaceOrderResponse> placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        PlaceOrderResponse response = placeOrderService.place(request);
        return ResponseEntity
                .created(URI.create("/api/orders/" + response.orderNo()))
                .body(response);
    }

    /** 사가라는 비동기 작업을 받아둔 것이므로 202 다. 결과는 조회 API 로 본다. */
    @PostMapping("/{orderNo}/confirm")
    public ResponseEntity<ConfirmOrderResponse> confirm(@PathVariable String orderNo,
                                                        @Valid @RequestBody ConfirmOrderRequest request) {
        ConfirmOrderResponse response =
                confirmOrderService.confirm(orderNo, request.paymentKey(), request.amount());
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(response);
    }

    @GetMapping("/{orderNo}")
    public ResponseEntity<OrderTraceResponse> findByOrderNo(@PathVariable String orderNo) {
        return orderQueryService.findByOrderNo(orderNo)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
