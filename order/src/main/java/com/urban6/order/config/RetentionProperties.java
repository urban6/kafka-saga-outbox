package com.urban6.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "retention")
public record RetentionProperties(
		Duration scanInterval,
		Duration outbox,
		Duration consumedMessage,
		Duration apiIdempotency,
		int batchSize,
		int maxBatchesPerRun) {
}
