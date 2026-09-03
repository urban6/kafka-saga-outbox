package com.urban6.order.infra.messaging;

import java.math.BigDecimal;

/**
 * Order → Payment. 사가의 유일한 원격 단계인 결제 승인 요청.
 * <p>
 * {@code paymentKey} 는 결제창(브라우저)이 인증을 마치고 프론트에 건넨 값이다. order 는 이걸
 * 해석하지 않고 <b>통과만 시킨다</b> — PG 가 발급한 불투명 문자열이고, 승인은 이 키 없이는 안 된다.
 * 결제수단이나 멱등키 헤더 같은 PG 대화의 세부는 여전히 담지 않는다. 그건 payment 와 PG 사이의 일이다.
 * <p>
 * API 요청 DTO 를 재사용하지 않는 이유는 계약의 수명이 다르기 때문이다. HTTP 요청 필드를
 * 바꿨다고 카프카 와이어 포맷이 딸려 깨지면 안 된다.
 */
public record ApprovePaymentCommand(
		String orderNo,
		BigDecimal amount,
		String paymentKey
) {
}
