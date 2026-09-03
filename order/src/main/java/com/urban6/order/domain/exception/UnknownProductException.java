package com.urban6.order.domain.exception;

import lombok.Getter;

/**
 * 존재하지 않는 상품. DTO 검증으로는 알 수 없고 DB 를 봐야 아는 것이라 서비스가 던진다.
 *
 * 전용 예외인 이유는 응답 문구다 — IllegalArgumentException 을 그대로 400 으로 내보내면
 * 예외 메시지(영문)가 API 응답에 실려 "API 응답 한글" 규칙이 깨지고,
 * 그 핸들러가 catch-all 이라 다른 예외의 내부 메시지까지 함께 새어나간다.
 */
@Getter
public class UnknownProductException extends RuntimeException {

    private final String productId;

    public UnknownProductException(String productId) {
        super("unknown product. productId=" + productId);
        this.productId = productId;
    }

}
