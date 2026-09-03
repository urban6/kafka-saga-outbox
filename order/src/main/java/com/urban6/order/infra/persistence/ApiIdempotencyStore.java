package com.urban6.order.infra.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 주문 API 멱등. 클라이언트가 만든 {@code Idempotency-Key} 로 같은 요청의 재시도를 흡수한다.
 * <p>
 * {@code consumed_message} 와 구조는 같지만 <b>결과({@code order_no})까지 보관</b>한다.
 * Kafka 는 중복이면 아무것도 안 하고 끝나면 되지만, HTTP 는 두 번째 요청에도 응답을 돌려줘야 한다.
 * <p>
 * 선점은 조회 없이 {@code insert ignore} 한 문장이다. "확인 후 INSERT" 로 나누면 그 사이를
 * 동시 요청이 파고들어 주문이 둘 생긴다. 갱신 행 수가 곧 판정 결과다.
 */
@Repository
@RequiredArgsConstructor
public class ApiIdempotencyStore {

    private static final String CLAIM = """
            insert ignore into api_idempotency
                (idempotency_key, request_hash, order_no, created_at)
            values (?, ?, ?, ?)
            """;

    private static final String FIND =
            "select request_hash, order_no from api_idempotency where idempotency_key = ?";

    private static final String PURGE = "delete from api_idempotency where created_at < ?";

    private final JdbcTemplate jdbcTemplate;

    /** 처음 보는 키면 true. 이미 있으면 false 이므로 호출부가 재생으로 분기한다. */
    public boolean claim(String idempotencyKey, String requestHash, String orderNo) {
        int inserted = jdbcTemplate.update(CLAIM,
                idempotencyKey, requestHash, orderNo, Timestamp.from(Instant.now()));
        return inserted > 0;
    }

    /** 재생에 쓸 기존 기록. {@link #claim} 이 false 를 돌려줬을 때만 부른다. */
    public Optional<Claimed> find(String idempotencyKey) {
        List<Claimed> rows = jdbcTemplate.query(FIND,
                (rs, rowNum) -> new Claimed(rs.getString("request_hash"), rs.getString("order_no")),
                idempotencyKey);
        return rows.stream().findFirst();
    }

    /** 보관 주기가 지난 기록 정리. {@code idx_created} 를 탄다. */
    public int purgeCreatedBefore(Instant threshold) {
        return jdbcTemplate.update(PURGE, Timestamp.from(threshold));
    }

    public record Claimed(String requestHash, String orderNo) {
    }
}
