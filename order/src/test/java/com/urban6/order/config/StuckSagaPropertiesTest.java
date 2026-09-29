package com.urban6.order.config;

import com.urban6.order.domain.SagaStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** minThreshold() 가 긴 값을 고르면 짧은 단계의 정체가 후보 조회에서 통째로 빠진다. */
class StuckSagaPropertiesTest {

	private static final Duration DEFAULT_THRESHOLD = Duration.ofMinutes(2);

	private static StuckSagaProperties propertiesWith(Map<SagaStep, Duration> thresholds) {
		return new StuckSagaProperties(Duration.ofSeconds(30), DEFAULT_THRESHOLD, thresholds, 100);
	}

	@Test
	@DisplayName("설정된 단계는 그 단계의 임계값을 쓴다")
	void thresholdFor_configuredStep() {
		StuckSagaProperties properties =
				propertiesWith(Map.of(SagaStep.APPROVE_PAYMENT, Duration.ofSeconds(60)));

		assertThat(properties.thresholdFor(SagaStep.APPROVE_PAYMENT))
				.isEqualTo(Duration.ofSeconds(60));
	}

	@Test
	@DisplayName("설정되지 않은 단계는 기본 임계값으로 떨어진다")
	void thresholdFor_unconfiguredStep() {
		StuckSagaProperties properties = propertiesWith(Map.of());

		assertThat(properties.thresholdFor(SagaStep.APPROVE_PAYMENT)).isEqualTo(DEFAULT_THRESHOLD);
	}

	@Test
	@DisplayName("thresholds 를 통째로 빼도 NPE 없이 기본값으로 동작한다")
	void nullThresholds_fallsBackToDefault() {
		StuckSagaProperties properties = propertiesWith(null);

		assertThat(properties.thresholds()).isEmpty();
		assertThat(properties.thresholdFor(SagaStep.APPROVE_PAYMENT)).isEqualTo(DEFAULT_THRESHOLD);
		assertThat(properties.minThreshold()).isEqualTo(DEFAULT_THRESHOLD);
	}

	@Test
	@DisplayName("minThreshold 는 단계 임계값이 기본값보다 짧으면 그 값을 고른다")
	void minThreshold_picksShorterStepThreshold() {
		StuckSagaProperties properties =
				propertiesWith(Map.of(SagaStep.APPROVE_PAYMENT, Duration.ofSeconds(60)));

		assertThat(properties.minThreshold()).isEqualTo(Duration.ofSeconds(60));
	}

	@Test
	@DisplayName("minThreshold 는 모든 단계 임계값이 기본값보다 길면 기본값을 고른다")
	void minThreshold_picksDefaultWhenAllStepsAreLonger() {
		StuckSagaProperties properties =
				propertiesWith(Map.of(SagaStep.APPROVE_PAYMENT, Duration.ofMinutes(5)));

		assertThat(properties.minThreshold()).isEqualTo(DEFAULT_THRESHOLD);
	}

}
