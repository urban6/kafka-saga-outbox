package com.urban6.order.config;

import com.urban6.order.domain.SagaStep;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * Stuck 탐지 설정. 임계값이 단계별 Map 인 것은 단계마다 정상 소요 시간이 달라서다.
 *
 * @param scanInterval 자바에서 읽지 않는다. @Scheduled 가 직접 읽고, 여기엔 메타데이터로만 둔다
 */
@ConfigurationProperties(prefix = "saga.stuck")
public record StuckSagaProperties(
		Duration scanInterval,
		Duration defaultThreshold,
		Map<SagaStep, Duration> thresholds,
		int scanLimit) {

	public StuckSagaProperties {
		// 설정에서 thresholds 를 통째로 빼면 null 이 들어온다. 방어해두면 아래 메서드들이 분기 없이 끝난다.
		thresholds = (thresholds == null) ? Map.of() : Map.copyOf(thresholds);
	}

	public Duration thresholdFor(SagaStep step) {
		return thresholds.getOrDefault(step, defaultThreshold);
	}

	/**
	 * 후보 조회에 쓸 컷. 이보다 어린 사가는 어떤 단계여도 stuck 이 아니다.
	 * 가장 짧은 임계값이어야 한다 — 가장 긴 값으로 자르면 짧은 단계의 정체를 통째로 놓친다.
	 */
	public Duration minThreshold() {
		return thresholds.values().stream()
				.min(Duration::compareTo)
				.filter(shortest -> shortest.compareTo(defaultThreshold) < 0)
				.orElse(defaultThreshold);
	}
}
