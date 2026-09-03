package com.urban6.order.api;

import com.urban6.order.api.dto.ErrorResponse;
import com.urban6.order.application.exception.IdempotencyConflictException;
import com.urban6.order.domain.exception.OutOfStockException;
import com.urban6.order.domain.exception.UnknownProductException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();

        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("INVALID_REQUEST", "요청 값이 올바르지 않습니다", details));
    }

    /**
     * 메서드 파라미터 제약 위반(헤더·경로변수).
     * 이 핸들러가 없으면 아래 Exception 핸들러가 프레임워크의 400 을 가로채 500 으로 만든다.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleParameterValidation(HandlerMethodValidationException e) {
        List<String> details = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> result.getMethodParameter().getParameterName()
                                + ": " + error.getDefaultMessage()))
                .toList();

        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("INVALID_REQUEST", "요청 값이 올바르지 않습니다", details));
    }

    /** 헤더 누락. Idempotency-Key 가 빠진 주문 요청이 여기로 온다. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("MISSING_HEADER", "필수 헤더가 없습니다", List.of(e.getHeaderName())));
    }

    /** 문법은 멀쩡하고 키를 재사용한 것이라 400 이 아니라 422 다. 고칠 곳은 본문이 아니라 키다. */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException e) {
        log.warn("idempotency key reused. {}", e.getMessage());

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ErrorResponse.of("IDEMPOTENCY_KEY_REUSED",
                        "같은 Idempotency-Key 로 다른 주문을 보낼 수 없습니다", List.of(e.getIdempotencyKey())));
    }

    /**
     * 없는 상품. 요청 형식은 멀쩡하고 값이 실재하지 않는 것이라 400 이다.
     *
     * IllegalArgumentException 을 통째로 받는 핸들러를 두지 않는다 — 그러면
     * Order 의 불변조건 가드(quantity/unitPrice)까지 400 으로 내려가고 예외 메시지가 응답에 실린다.
     * 그 둘은 도달하면 서버 버그라 500 이 맞고, 그건 마지막 핸들러가 이미 한다.
     */
    @ExceptionHandler(UnknownProductException.class)
    public ResponseEntity<ErrorResponse> handleUnknownProduct(UnknownProductException e) {
        log.warn("order rejected. {}", e.getMessage());

        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("UNKNOWN_PRODUCT", "존재하지 않는 상품입니다",
                        List.of(e.getProductId())));
    }

    @ExceptionHandler(OutOfStockException.class)
    public ResponseEntity<ErrorResponse> handleOutOfStock(OutOfStockException e) {
        log.warn("order rejected. {}", e.getMessage());

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("OUT_OF_STOCK", "재고가 부족한 상품이 있습니다",
                        List.of(e.getProductId() + ": " + e.getRequestedQuantity() + "개 요청")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("unhandled exception", e);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "서버 오류가 발생했습니다", List.of()));
    }
}
