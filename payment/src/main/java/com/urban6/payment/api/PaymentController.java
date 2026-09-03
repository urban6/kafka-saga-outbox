package com.urban6.payment.api;

import com.urban6.payment.api.dto.ApproveRequest;
import com.urban6.payment.api.dto.BillingKeyResponse;
import com.urban6.payment.api.dto.PaymentResponse;
import com.urban6.payment.api.dto.RegisterBillingKeyRequest;
import com.urban6.payment.application.ApprovePaymentService;
import com.urban6.payment.application.RegisterBillingKeyService;
import com.urban6.payment.infra.client.PgCallException;
import com.urban6.payment.infra.persistence.PaymentRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 API.
 * <p>
 * {@code POST /api/payments} 는 임시 테스트용이 아니다. {@code @KafkaListener} 가
 * <b>같은 {@link ApprovePaymentService} 를 부른다</b> — 진입 경로가 둘일 뿐 유스케이스는 하나다.
 * 운영에서도 "수동 재승인" 용도로 남는다.
 * <p>
 * 카드 등록은 사가와 무관한 동기 HTTP 다. 주문 전에 한 번 해두는 것이고, 결제 시점엔 커맨드의
 * {@code customerId} 로 여기서 저장한 키를 찾는다.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

	/** PG 에러 응답을 그대로 노출하지 않고 우리 형식으로 감싼다. 코드는 그대로 — 원인 추적에 필요하다. */
	public record ErrorResponse(String code, String message) {
	}

	private final ApprovePaymentService approvePaymentService;
	private final RegisterBillingKeyService registerBillingKeyService;
	private final PaymentRepository paymentRepository;

	public PaymentController(ApprovePaymentService approvePaymentService,
			RegisterBillingKeyService registerBillingKeyService,
			PaymentRepository paymentRepository) {
		this.approvePaymentService = approvePaymentService;
		this.registerBillingKeyService = registerBillingKeyService;
		this.paymentRepository = paymentRepository;
	}

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

	/**
	 * PG 가 거절한 발급. PG 의 4xx 는 우리 입력이 틀린 것(카드 정보)이라 400 으로,
	 * 5xx 는 PG 장애라 502 로 돌려준다. 우리 서버 문제(500)와 구분되어야 한다.
	 */
	@ExceptionHandler(PgCallException.class)
	public ResponseEntity<ErrorResponse> handlePgCall(PgCallException e) {
		HttpStatus status = e.getHttpStatus() >= 500 ? HttpStatus.BAD_GATEWAY : HttpStatus.BAD_REQUEST;
		return ResponseEntity.status(status)
				.body(new ErrorResponse(e.getCode(), "PG 요청이 거절되었습니다: " + e.getMessage()));
	}
}
