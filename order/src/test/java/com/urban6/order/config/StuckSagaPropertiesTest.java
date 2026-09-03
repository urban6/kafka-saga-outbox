package com.urban6.order.config;

import com.urban6.order.domain.SagaStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 임계값 계산은 순수 함수라 스프링 없이 전 조합을 돌린다.
 * <p>
 * {@code minThreshold()} 가 이 클래스에서 가장 중요하다. 여기서 <b>가장 긴</b> 값을 고르면
 * 짧은 임계값을 가진 단계의 정체가 후보 조회에서 통째로 빠져 영영 탐지되지 않는다.
 * 로그도 메트릭도 조용해서 눈으로는 안 드러난다.
 */
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
		// SagaStep 이 하나뿐이라 "다른 단계" 를 만들 수 없다. 빈 설정으로 같은 경로를 탄다.
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
		// 임계값이 지정되지 않은 단계가 하나라도 있으면 기본값이 실제로 쓰이므로,
		// 후보 컷은 기본값까지 내려가야 한다.
		StuckSagaProperties properties =
				propertiesWith(Map.of(SagaStep.APPROVE_PAYMENT, Duration.ofMinutes(5)));

		assertThat(properties.minThreshold()).isEqualTo(DEFAULT_THRESHOLD);
	}

}
