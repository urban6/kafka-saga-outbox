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

/** stepStartedAt 을 바꿀 수단이 없어 now 를 민다. */
class StuckSagaDetectorTest {

	private static final Duration THRESHOLD = Duration.ofSeconds(60);

	private static StuckSagaDetector detector() {
		StuckSagaProperties properties = new StuckSagaProperties(
				Duration.ofSeconds(30),
				Duration.ofMinutes(2),
				Map.of(SagaStep.APPROVE_PAYMENT, THRESHOLD),
				100);

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
		// >= 로 둔다. 초과로 두면 한 스캔 주기만큼 감지가 늦어진다.
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
