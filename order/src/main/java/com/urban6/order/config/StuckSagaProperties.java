package com.urban6.order.config;

import com.urban6.order.domain.SagaStep;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

@ConfigurationProperties(prefix = "saga.stuck")
public record StuckSagaProperties(
		Duration scanInterval,
		Duration defaultThreshold,
		Map<SagaStep, Duration> thresholds,
		int scanLimit) {

	public StuckSagaProperties {
		thresholds = (thresholds == null) ? Map.of() : Map.copyOf(thresholds);
	}

	public Duration thresholdFor(SagaStep step) {
		return thresholds.getOrDefault(step, defaultThreshold);
	}

	/** 가장 짧은 임계값이어야 한다. 긴 값으로 자르면 짧은 단계의 정체를 놓친다. */
	public Duration minThreshold() {
		return thresholds.values().stream()
				.min(Duration::compareTo)
				.filter(shortest -> shortest.compareTo(defaultThreshold) < 0)
				.orElse(defaultThreshold);
	}
}
