package com.urban6.payment.api;

import com.urban6.payment.api.dto.ApproveRequest;
import com.urban6.payment.api.dto.BillingKeyResponse;
import com.urban6.payment.api.dto.PaymentResponse;
import com.urban6.payment.api.dto.RegisterBillingKeyRequest;
import com.urban6.payment.application.ApprovePaymentService;
import com.urban6.payment.application.RegisterBillingKeyService;
import com.urban6.payment.infra.persistence.PaymentRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final ApprovePaymentService approvePaymentService;
    private final RegisterBillingKeyService registerBillingKeyService;
    private final PaymentRepository paymentRepository;

    @PostMapping("/billing-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public BillingKeyResponse registerBillingKey(@Valid @RequestBody RegisterBillingKeyRequest request) {
        return BillingKeyResponse.from(
                registerBillingKeyService.register(request.customerId(), request.cardNumber()));
    }

    @PostMapping
    public PaymentResponse approve(@Valid @RequestBody ApproveRequest request) {
        return PaymentResponse.from(
                approvePaymentService.approve(request.orderNo(), request.customerId(), request.amount()));
    }

    @GetMapping("/{orderNo}")
    public ResponseEntity<PaymentResponse> findByOrderNo(@PathVariable String orderNo) {
        return paymentRepository.findByOrderNo(orderNo)
                .map(PaymentResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
