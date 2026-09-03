package com.urban6.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 정리 배치 보관 주기.
 * <p>
 * 기준은 전부 <b>시간</b>이다. "사가가 끝났으니 지운다" 같은 기준을 쓰면 안 된다 —
 * 애플리케이션은 outbox 행이 발행됐는지 모른다(Debezium 이 binlog 로 읽어간다).
 * 완료된 사가의 행을 지웠는데 커넥터가 아직 안 읽었으면 그 이벤트는 영영 나가지 않는다.
 *
 * @param scanInterval     배치 주기. 자바에서 읽지 않고 {@code @Scheduled} 가 직접 읽는다
 * @param outbox           outbox 보관 기간. 커넥터 지연보다 충분히 길어야 한다
 * @param consumedMessage  멱등 이력 보관 기간. <b>Kafka retention 보다 길어야</b> 한다
 * @param apiIdempotency   API 멱등 기록 보관 기간. 클라이언트 재시도 창보다 길면 된다
 * @param batchSize        DELETE 한 번의 상한. 무제한 DELETE 는 락을 오래 잡는다
 * @param maxBatchesPerRun 한 회차의 반복 상한. 밀린 양이 많아도 이 배치가 DB 를 독점하지 않게 한다
 */
@ConfigurationProperties(prefix = "retention")
public record RetentionProperties(
		Duration scanInterval,
		Duration outbox,
		Duration consumedMessage,
		Duration apiIdempotency,
		int batchSize,
		int maxBatchesPerRun) {
}
