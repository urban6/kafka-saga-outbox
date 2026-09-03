package com.urban6.order.config;

import com.urban6.order.domain.SagaStep;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * Stuck 탐지 설정.
 *
 * 임계값을 단계별 Map 으로 두는 이유: 단계마다 정상 소요 시간이 다르다.
 * PG 청구는 수십 초, 배송사 연동이라면 수 분이다. 글로벌 타임아웃 하나로 자르면
 * 정상적으로 오래 걸리는 단계가 전부 오탐이 된다.
 *
 * 계산 메서드는 전부 순수 함수다. OrderSagaOrchestrator.decide() 와 같은 성격으로,
 * 스프링 없이 전 조합을 단위 테스트할 수 있다.
 *
 * @param scanInterval     스캔 주기. 이 값은 자바에서 읽지 않고 @Scheduled 가
 *                         ${saga.stuck.scan-interval} 로 직접 읽는다.
 *                         여기 선언해두는 건 설정 메타데이터와 문서를 한곳에 모으기 위해서다
 * @param defaultThreshold 임계값이 지정되지 않은 단계에 쓰는 값
 * @param thresholds       단계별 임계값
 * @param scanLimit        한 번에 읽어올 최대 행 수
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
	 *
	 * SQL 은 step_started_at 하나로만 자를 수 있는데 임계값은 단계마다 다르다.
	 * 가장 짧은 임계값으로 넉넉히 뽑아 인덱스를 타고, 단계별 판정은 메모리에서 한다.
	 * 여기서 가장 긴 값을 쓰면 짧은 단계의 stuck 을 놓친다.
	 */
	public Duration minThreshold() {
		return thresholds.values().stream()
				.min(Duration::compareTo)
				.filter(shortest -> shortest.compareTo(defaultThreshold) < 0)
				.orElse(defaultThreshold);
	}
}
