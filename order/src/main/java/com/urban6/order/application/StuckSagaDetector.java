package com.urban6.order.application;

import com.urban6.order.config.StuckSagaProperties;
import com.urban6.order.domain.SagaInstance;
import com.urban6.order.domain.SagaStatus;
import com.urban6.order.domain.SagaStep;
import com.urban6.order.infra.persistence.SagaInstanceRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** 예외 없이 멈춘 사가(발행 실패·회신 유실)를 탐지만 한다. 고치지 않는다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class StuckSagaDetector {

	// isTerminated() 의 여집합. 상태가 늘 때 추가를 잊으면 그 사가는 영영 안 보인다.
	private static final List<SagaStatus> ACTIVE = List.of(SagaStatus.STARTED);

	private static final int LOG_SAMPLE = 10;

	private final SagaInstanceRepository sagaInstanceRepository;
	private final StuckSagaProperties properties;
	private final MeterRegistry meterRegistry;

	private final Map<SagaStep, AtomicLong> stuckCount = new EnumMap<>(SagaStep.class);
	private final Map<SagaStep, AtomicLong> oldestAgeSeconds = new EnumMap<>(SagaStep.class);

	/** 사라진 메트릭에는 알람을 못 건다. 모든 단계를 0 으로라도 등록한다. */
	@PostConstruct
	void registerGauges() {
		// Micrometer 는 약참조다. AtomicLong 을 필드에 붙들지 않으면 GC 뒤 NaN 이 된다.
		for (SagaStep step : SagaStep.values()) {
			AtomicLong count = new AtomicLong();
			AtomicLong age = new AtomicLong();
			stuckCount.put(step, count);
			oldestAgeSeconds.put(step, age);

			Gauge.builder("saga.stuck.count", count, AtomicLong::get)
					.tag("step", step.name())
					.description("threshold 를 넘겨 이 단계에 머물러 있는 사가 수. scan-limit 에서 포화될 수 있다")
					.register(meterRegistry);

			Gauge.builder("saga.stuck.oldest.age.seconds", age, AtomicLong::get)
					.tag("step", step.name())
					.baseUnit("seconds")
					.description("이 단계에서 가장 오래 정체된 사가의 나이. 알람은 이 값으로 건다")
					.register(meterRegistry);
		}
	}

	// fixedRate 면 느려진 스캔이 겹쳐 쌓인다.
	@Scheduled(fixedDelayString = "${saga.stuck.scan-interval}")
	@Transactional(readOnly = true)
	public void scan() {
		Instant now = Instant.now();
		int limit = properties.scanLimit();

		// 가장 짧은 임계값으로 자르고 단계별 판정은 filter 가 한다.
		List<SagaInstance> candidates = sagaInstanceRepository.findStuckCandidates(
				ACTIVE, now.minus(properties.minThreshold()), PageRequest.of(0, limit));

		List<SagaInstance> stuck = candidates.stream()
				.filter(saga -> isStuck(saga, now))
				.toList();

		publishGauges(stuck, now);

		if (stuck.isEmpty()) {
			log.debug("stuck saga scan clean. candidates={}", candidates.size());
			return;
		}

		SagaInstance oldest = stuck.getFirst();

		log.error("stuck saga detected. count={}{} oldestOrderNo={} oldestStep={} oldestAgeSeconds={} orderNos=[{}{}]",
				stuck.size(),
				candidates.size() == limit ? " (capped at scan-limit)" : "",
				oldest.getOrderNo(),
				oldest.getCurrentStep(),
				ageSeconds(oldest, now),
				stuck.stream().limit(LOG_SAMPLE).map(SagaInstance::getOrderNo)
						.collect(Collectors.joining(", ")),
				stuck.size() > LOG_SAMPLE ? ", ..." : "");
	}

	/** 정체가 없는 단계에도 0 을 쓴다. 안 쓰면 해소된 뒤에도 알람이 안 꺼진다. */
	private void publishGauges(List<SagaInstance> stuck, Instant now) {
		Map<SagaStep, List<SagaInstance>> byStep = stuck.stream()
				.collect(Collectors.groupingBy(SagaInstance::getCurrentStep));

		for (SagaStep step : SagaStep.values()) {
			List<SagaInstance> rows = byStep.getOrDefault(step, List.of());
			stuckCount.get(step).set(rows.size());
			oldestAgeSeconds.get(step).set(rows.isEmpty() ? 0L : ageSeconds(rows.getFirst(), now));
		}
	}

	/** 임계값 정각도 정체로 본다(>=). */
	boolean isStuck(SagaInstance saga, Instant now) {
		return Duration.between(saga.getStepStartedAt(), now)
				.compareTo(properties.thresholdFor(saga.getCurrentStep())) >= 0;
	}

	private static long ageSeconds(SagaInstance saga, Instant now) {
		return Duration.between(saga.getStepStartedAt(), now).toSeconds();
	}
}
