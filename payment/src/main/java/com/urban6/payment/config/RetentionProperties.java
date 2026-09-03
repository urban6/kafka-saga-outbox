package com.urban6.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 정리 배치 보관 주기.
 * <p>
 * 기준은 전부 <b>시간</b>이다. "결제가 끝났으니 지운다" 같은 기준을 쓰면 안 된다 —
 * 애플리케이션은 outbox 행이 발행됐는지 모른다(Debezium 이 binlog 로 읽어간다).
 * <p>
 * order 와 같은 모양이지만 <b>클래스를 공유하지 않는다.</b> 보관 기간은 서비스마다 다르게 정할 수 있어야 하고,
 * 여기 필드를 하나 늘리려고 다른 서비스를 재빌드하게 만들면 독립 배포가 깨진다.
 * ({@code api_idempotency} 는 order 에만 있으므로 이 record 에는 없다)
 *
 * @param scanInterval     배치 주기. 자바에서 읽지 않고 {@code @Scheduled} 가 직접 읽는다
 * @param outbox           outbox 보관 기간. 커넥터 지연보다 충분히 길어야 한다
 * @param consumedMessage  멱등 이력 보관 기간. <b>Kafka retention 보다 길어야</b> 한다
 * @param batchSize        DELETE 한 번의 상한. 무제한 DELETE 는 락을 오래 잡는다
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
