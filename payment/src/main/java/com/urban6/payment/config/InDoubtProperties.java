package com.urban6.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** @param scanInterval 자바에서 읽지 않는다. @Scheduled 가 직접 읽는다 */
@ConfigurationProperties(prefix = "payment.in-doubt")
public record InDoubtProperties(
		Duration scanInterval,
		Duration grace,
		Duration escalateAfter,
		int scanLimit) {
}
