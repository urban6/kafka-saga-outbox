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

    /** 없으면 마지막 Exception 핸들러가 프레임워크의 400 을 500 으로 만든다. */
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

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("MISSING_HEADER", "필수 헤더가 없습니다", List.of(e.getHeaderName())));
    }

    /** 본문이 아니라 키를 재사용한 것이라 422 다. */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException e) {
        log.warn("idempotency key reused. {}", e.getMessage());

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ErrorResponse.of("IDEMPOTENCY_KEY_REUSED",
                        "같은 Idempotency-Key 로 다른 주문을 보낼 수 없습니다", List.of(e.getIdempotencyKey())));
    }

    /** IllegalArgumentException 을 통째로 받지 않는다. Order 불변조건 가드까지 400 이 되고 메시지가 샌다. */
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
