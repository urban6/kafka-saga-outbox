package com.urban6.order.api;

import com.urban6.order.application.OrderQueryService;
import com.urban6.order.application.PlaceOrderService;
import com.urban6.order.api.dto.OrderTraceResponse;
import com.urban6.order.api.dto.PlaceOrderRequest;
import com.urban6.order.api.dto.PlaceOrderResponse;
import jakarta.validation.Valid;
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

import java.net.URI;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final PlaceOrderService placeOrderService;
    private final OrderQueryService orderQueryService;

    /**
     * Idempotency-Key 는 필수다. 돈이 빠지는 요청이라 헤더를 빠뜨린 클라이언트에게
     * 조용히 이중 결제를 허용하지 않는다. 재시도할 때는 같은 키를 다시 보내야 한다.
     */
    @PostMapping
    public ResponseEntity<PlaceOrderResponse> placeOrder(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request) {
        PlaceOrderResponse response = placeOrderService.place(idempotencyKey, request);
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
