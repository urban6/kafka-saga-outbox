package com.urban6.order.infra.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 주문 API 멱등. 클라이언트가 만든 Idempotency-Key 로 같은 요청의 재시도를 흡수한다.
 *
 * consumed_message 와 구조는 같지만 결과(order_no)까지 보관한다.
 * Kafka 는 중복이면 아무것도 안 하고 끝나면 되지만, HTTP 는 두 번째 요청에도 응답을 돌려줘야 한다.
 *
 * 선점은 조회 없이 INSERT 한 문장이다. "확인 후 INSERT" 로 나누면 그 사이를
 * 동시 요청이 파고들어 주문이 둘 생긴다. 유니크 인덱스 충돌 여부가 곧 판정 결과다.
 */
@Repository
@RequiredArgsConstructor
public class ApiIdempotencyStore {

    private static final String CLAIM = """
            insert into api_idempotency
                (idempotency_key, request_hash, order_no, created_at)
            values (?, ?, ?, ?)
            """;

    private static final String FIND =
            "select request_hash, order_no from api_idempotency where idempotency_key = ? for update";

    private static final String PURGE = "delete from api_idempotency where created_at < ? limit ?";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 처음 보는 키면 true. 이미 있으면 false 이므로 호출부가 재생으로 분기한다.
     *
     * insert ignore 가 아닌 이유: 그건 truncation 까지 삼켜서, 앞 128자가 같은 긴 키 둘이
     * 한 행으로 병합된다. 중복만 잡고 나머지는 던진다.
     */
    public boolean claim(String idempotencyKey, String requestHash, String orderNo) {
        try {
            jdbcTemplate.update(CLAIM,
                    idempotencyKey, requestHash, orderNo, Timestamp.from(Instant.now()));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 재생에 쓸 기존 기록. claim 이 false 를 돌려줬을 때만 부른다.
     *
     * for update 는 가시성 때문이다. REPEATABLE READ 스냅샷이 앞 요청의 커밋보다 먼저 잡히면
     * 방금 선점된 행이 안 보여 호출부가 500 을 낸다. locking read 는 항상 최신 커밋본을 읽는다.
     */
    public Optional<Claimed> find(String idempotencyKey) {
        List<Claimed> rows = jdbcTemplate.query(FIND,
                (rs, rowNum) -> new Claimed(rs.getString("request_hash"), rs.getString("order_no")),
                idempotencyKey);
        return rows.stream().findFirst();
    }

    /**
     * 보관 주기가 지난 기록 정리. idx_created 를 탄다.
     *
     * 이 표는 consumed_message 와 달리 결과(order_no)까지 들고 있다.
     * 클라이언트 재시도 창보다 길게만 잡으면 되므로 outbox 와 같은 기준으로 충분하다.
     */
    public int purgeCreatedBefore(Instant threshold, int batchSize) {
        return jdbcTemplate.update(PURGE, Timestamp.from(threshold), batchSize);
    }

    public record Claimed(String requestHash, String orderNo) {
    }
}
