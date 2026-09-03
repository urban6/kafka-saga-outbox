package com.urban6.order.api;

import com.urban6.order.application.OrderQueryService;
import com.urban6.order.application.PlaceOrderCommand;
import com.urban6.order.application.PlaceOrderService;
import com.urban6.order.api.dto.OrderTraceResponse;
import com.urban6.order.api.dto.PlaceOrderRequest;
import com.urban6.order.api.dto.PlaceOrderResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final PlaceOrderService placeOrderService;
    private final OrderQueryService orderQueryService;

    @PostMapping
    public ResponseEntity<PlaceOrderResponse> placeOrder(
            @RequestHeader("Idempotency-Key")
            @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request) {
        PlaceOrderResponse response = placeOrderService.place(idempotencyKey, toCommand(request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * HTTP 표현을 유스케이스 입력으로 옮긴다. 지금은 필드가 같지만 계약의 수명이 달라 분리해 둔다 —
     * 멱등 지문이 커맨드로 계산되므로, 여기서 갈라놔야 API 필드를 늘려도 지문이 안 움직인다.
     */
    private static PlaceOrderCommand toCommand(PlaceOrderRequest request) {
        return new PlaceOrderCommand(request.customerId(), request.items().stream()
                .map(item -> new PlaceOrderCommand.Item(item.productId(), item.quantity()))
                .toList());
    }

    @GetMapping("/{orderNo}")
    public ResponseEntity<OrderTraceResponse> findByOrderNo(@PathVariable String orderNo) {
        return orderQueryService.findByOrderNo(orderNo)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
