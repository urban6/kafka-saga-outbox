package com.urban6.order.api;

import com.urban6.order.domain.OrderNotConfirmableException;
import com.urban6.order.domain.OrderNotFoundException;
import com.urban6.order.domain.OutOfStockException;
import com.urban6.order.domain.PaymentAmountMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOrderNotFound(OrderNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("ORDER_NOT_FOUND", "주문을 찾을 수 없습니다", List.of(e.getOrderNo())));
    }

    /** 금액이 다르면 승인하지 않는다. 얼마가 맞는지는 응답에 싣지 않는다 — 맞춰서 다시 보내라는 뜻이 아니다. */
    @ExceptionHandler(PaymentAmountMismatchException.class)
    public ResponseEntity<ErrorResponse> handleAmountMismatch(PaymentAmountMismatchException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("AMOUNT_MISMATCH", "결제 금액이 주문 금액과 일치하지 않습니다", List.of()));
    }

    @ExceptionHandler(OrderNotConfirmableException.class)
    public ResponseEntity<ErrorResponse> handleNotConfirmable(OrderNotConfirmableException e) {
        log.info("confirm rejected. {}", e.getMessage());

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("ORDER_NOT_CONFIRMABLE", "결제를 진행할 수 없는 주문입니다",
                        List.of("status: " + e.getStatus())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("unhandled exception", e);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "서버 오류가 발생했습니다", List.of()));
    }
}
