package com.urban6.order.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 에러 응답 본문. 성공 응답과 똑같이 API 계약이라 dto 에 둔다.
 *
 * @param details 필드별 상세. 없으면 빈 리스트
 */
public record ErrorResponse(String code, String message, List<String> details, Instant timestamp) {

    public static ErrorResponse of(String code, String message, List<String> details) {
        return new ErrorResponse(code, message, details, Instant.now());
    }
}
