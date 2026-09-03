package com.urban6.order.api;

import com.urban6.order.application.IdempotencyConflictException;
import com.urban6.order.domain.OutOfStockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.time.Instant;
import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ErrorResponse(String code, String message, List<String> details, Instant timestamp) {

        static ErrorResponse of(String code, String message, List<String> details) {
            return new ErrorResponse(code, message, details, Instant.now());
        }
    }

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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("INVALID_ARGUMENT", e.getMessage(), List.of()));
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
