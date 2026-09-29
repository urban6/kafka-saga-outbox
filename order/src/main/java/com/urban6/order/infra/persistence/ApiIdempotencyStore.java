package com.urban6.order.infra.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** consumed_message 와 달리 결과(order_no)까지 보관한다. HTTP 는 중복에도 응답을 돌려줘야 한다. */
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

    // insert ignore 를 안 쓴다. 그건 truncation 까지 삼켜 앞 128자가 같은 긴 키 둘이 한 행으로 병합된다.
    public boolean claim(String idempotencyKey, String requestHash, String orderNo) {
        try {
            jdbcTemplate.update(CLAIM,
                    idempotencyKey, requestHash, orderNo, Timestamp.from(Instant.now()));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    // for update: REPEATABLE READ 스냅샷이면 앞 요청이 방금 커밋한 행이 안 보여 500 이 난다.
    public Optional<Claimed> find(String idempotencyKey) {
        List<Claimed> rows = jdbcTemplate.query(FIND,
                (rs, rowNum) -> new Claimed(rs.getString("request_hash"), rs.getString("order_no")),
                idempotencyKey);
        return rows.stream().findFirst();
    }

    public int purgeCreatedBefore(Instant threshold, int batchSize) {
        return jdbcTemplate.update(PURGE, Timestamp.from(threshold), batchSize);
    }

    public record Claimed(String requestHash, String orderNo) {
    }
}
