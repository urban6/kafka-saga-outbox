package com.urban6.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 정리 배치 보관 주기. 기준은 전부 시간이다 — "사가가 끝났으니 지운다" 를 쓰면 안 된다.
 * 애플리케이션은 outbox 행이 발행됐는지 모른다.
 *
 * @param scanInterval     자바에서 읽지 않는다. @Scheduled 가 직접 읽는다
 * @param outbox           커넥터 지연보다 충분히 길어야 한다
 * @param consumedMessage  Kafka retention 보다 길어야 한다
 * @param apiIdempotency   클라이언트 재시도 창보다 길면 된다
 * @param batchSize        DELETE 한 번의 상한
 * @param maxBatchesPerRun 한 회차의 반복 상한
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
