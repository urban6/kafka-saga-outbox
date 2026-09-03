package com.urban6.payment.api;

import com.urban6.payment.api.dto.ApproveRequest;
import com.urban6.payment.api.dto.PaymentResponse;
import com.urban6.payment.application.ApprovePaymentService;
import com.urban6.payment.infra.persistence.PaymentRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 API.
 * <p>
 * {@code POST} 는 임시 테스트용이 아니다. 사가에 붙으면 {@code @KafkaListener} 가
 * <b>같은 {@link ApprovePaymentService} 를 부른다</b> — 진입 경로가 둘일 뿐 유스케이스는 하나다.
 * 운영에서도 "수동 재승인" 용도로 남는다.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

	private final ApprovePaymentService approvePaymentService;
	private final PaymentRepository paymentRepository;

	public PaymentController(ApprovePaymentService approvePaymentService,
			PaymentRepository paymentRepository) {
		this.approvePaymentService = approvePaymentService;
		this.paymentRepository = paymentRepository;
	}

	@PostMapping
	public PaymentResponse approve(@Valid @RequestBody ApproveRequest request) {
		return PaymentResponse.from(
				approvePaymentService.approve(request.orderNo(), request.paymentKey(), request.amount()));
	}

	@GetMapping("/{orderNo}")
	public ResponseEntity<PaymentResponse> findByOrderNo(@PathVariable String orderNo) {
		return paymentRepository.findByOrderNo(orderNo)
				.map(PaymentResponse::from)
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}
}
