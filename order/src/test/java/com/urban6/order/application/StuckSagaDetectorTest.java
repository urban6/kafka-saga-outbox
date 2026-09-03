package com.urban6.order.application;

import com.urban6.order.config.StuckSagaProperties;
import com.urban6.order.domain.SagaInstance;
import com.urban6.order.domain.SagaStep;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * isStuck 경계 검증.
 *
 * SagaInstance 는 stepStartedAt 을 바꿀 수단이 없다 — 전이를 조건부 UPDATE 로만
 * 하기로 했기 때문이다. 대신 isStuck 이 now 를 주입받으므로
 * 시작 시각을 고정하는 대신 현재 시각을 민다. 결과는 같고 프로덕션 코드는 그대로다.
 *
 * SagaStep 값이 하나뿐이라 "단계마다 임계값이 다르다" 는 성질은
 * StuckSagaPropertiesTest 가 설정 수준에서 본다.
 */
class StuckSagaDetectorTest {

	private static final Duration THRESHOLD = Duration.ofSeconds(60);

	private static StuckSagaDetector detector() {
		StuckSagaProperties properties = new StuckSagaProperties(
				Duration.ofSeconds(30),
				Duration.ofMinutes(2),
				Map.of(SagaStep.APPROVE_PAYMENT, THRESHOLD),
				100);

		// isStuck 은 리포지토리를 건드리지 않는다. null 이어도 도는 게 순수 함수라는 증거다.
		return new StuckSagaDetector(null, properties, new SimpleMeterRegistry());
	}

	private static SagaInstance saga() {
		return SagaInstance.start("ORD-20260903-TEST0001", "C-1", new BigDecimal("10000"));
	}

	@Test
	@DisplayName("임계값 직전은 정체가 아니다")
	void notStuck_justBeforeThreshold() {
		SagaInstance saga = saga();
		Instant now = saga.getStepStartedAt().plus(THRESHOLD).minusSeconds(1);

		assertThat(detector().isStuck(saga, now)).isFalse();
	}

	@Test
	@DisplayName("임계값 정각은 정체로 본다")
	void stuck_exactlyAtThreshold() {
		// 경계를 이상(>=)으로 둔 건 판단이다. 미만으로 두면 스캔 주기와 어긋나
		// "한 주기 더 지나야 뜨는" 지연이 임계값에 얹힌다.
		SagaInstance saga = saga();
		Instant now = saga.getStepStartedAt().plus(THRESHOLD);

		assertThat(detector().isStuck(saga, now)).isTrue();
	}

	@Test
	@DisplayName("임계값을 넘기면 정체다")
	void stuck_pastThreshold() {
		SagaInstance saga = saga();
		Instant now = saga.getStepStartedAt().plus(THRESHOLD).plusSeconds(1);

		assertThat(detector().isStuck(saga, now)).isTrue();
	}

	@Test
	@DisplayName("방금 시작한 사가는 정체가 아니다")
	void notStuck_justStarted() {
		SagaInstance saga = saga();

		assertThat(detector().isStuck(saga, saga.getStepStartedAt())).isFalse();
	}
}
