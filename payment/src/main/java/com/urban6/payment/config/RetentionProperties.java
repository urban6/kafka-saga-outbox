package com.urban6.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 정리 배치 보관 주기. 기준은 전부 시간이다 — 애플리케이션은 outbox 행이 발행됐는지 모른다.
 * order 와 같은 모양이지만 클래스를 공유하지 않는다.
 *
 * @param scanInterval     자바에서 읽지 않는다. @Scheduled 가 직접 읽는다
 * @param outbox           커넥터 지연보다 충분히 길어야 한다
 * @param consumedMessage  Kafka retention 보다 길어야 한다
 * @param batchSize        DELETE 한 번의 상한
 * @param maxBatchesPerRun 한 회차의 반복 상한
 */
@ConfigurationProperties(prefix = "retention")
public record RetentionProperties(
		Duration scanInterval,
		Duration outbox,
		Duration consumedMessage,
		int batchSize,
		int maxBatchesPerRun) {
}
