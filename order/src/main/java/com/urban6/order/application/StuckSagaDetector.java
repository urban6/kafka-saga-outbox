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

/**
 * 아무 예외도 나지 않는 실패 — Debezium 이 죽어 커맨드가 발행 안 되거나 회신이 유실된 경우 — 를
 * 주기적으로 스캔해 ERROR 로그와 Micrometer 게이지로 알린다. 탐지만 하고 아무것도 고치지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StuckSagaDetector {

	// 스캔 대상 = isTerminated() 의 여집합. 상태가 늘 때 여기 추가를 잊으면 그 사가는 영영 안 보인다.
	private static final List<SagaStatus> ACTIVE = List.of(SagaStatus.STARTED);

	/** 로그 한 줄에 담을 order_no 최대 개수. 나머지는 메트릭으로 본다. */
	private static final int LOG_SAMPLE = 10;

	private final SagaInstanceRepository sagaInstanceRepository;
	private final StuckSagaProperties properties;
	private final MeterRegistry meterRegistry;

	// 초기화된 final 필드라 @RequiredArgsConstructor 가 생성자에 넣지 않는다.
	private final Map<SagaStep, AtomicLong> stuckCount = new EnumMap<>(SagaStep.class);
	private final Map<SagaStep, AtomicLong> oldestAgeSeconds = new EnumMap<>(SagaStep.class);

	/**
	 * 모든 단계의 게이지를 0으로라도 미리 등록한다. 사라진 메트릭에는 알람을 못 건다.
	 */
	@PostConstruct
	void registerGauges() {
		// Micrometer 는 대상을 약참조로 잡는다. AtomicLong 을 필드 맵에 붙들지 않으면 GC 뒤 NaN 이 된다.
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

	// fixedDelay 다. fixedRate 로 두면 느려진 스캔이 겹쳐 쌓인다.
	@Scheduled(fixedDelayString = "${saga.stuck.scan-interval}")
	@Transactional(readOnly = true)
	public void scan() {
		Instant now = Instant.now();
		int limit = properties.scanLimit();

		// 가장 짧은 임계값으로 넉넉히 뽑는다. 단계별 판정은 아래 filter 가 한다.
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

		// 후보가 step_started_at ASC 로 오므로 필터를 통과한 첫 행이 전체에서 가장 오래됐다.
		SagaInstance oldest = stuck.getFirst();

		// 정체는 해소될 때까지 유지된다. 행마다 찍으면 주기마다 로그가 불어나므로 스캔당 한 줄로 묶는다.
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

	/** 정체가 없는 단계에도 0 을 쓴다. 건너뛰면 해소된 뒤에도 옛 값이 남아 알람이 안 꺼진다. */
	private void publishGauges(List<SagaInstance> stuck, Instant now) {
		Map<SagaStep, List<SagaInstance>> byStep = stuck.stream()
				.collect(Collectors.groupingBy(SagaInstance::getCurrentStep));

		for (SagaStep step : SagaStep.values()) {
			// groupingBy 가 입력 순서를 보존하므로 각 리스트의 첫 행이 그 단계에서 가장 오래된 것이다.
			List<SagaInstance> rows = byStep.getOrDefault(step, List.of());
			stuckCount.get(step).set(rows.size());
			oldestAgeSeconds.get(step).set(rows.isEmpty() ? 0L : ageSeconds(rows.getFirst(), now));
		}
	}

	/** 시각을 주입받는 순수 함수. 경계는 이상(>=)이라 임계값 정각도 정체로 본다. */
	boolean isStuck(SagaInstance saga, Instant now) {
		return Duration.between(saga.getStepStartedAt(), now)
				.compareTo(properties.thresholdFor(saga.getCurrentStep())) >= 0;
	}

	private static long ageSeconds(SagaInstance saga, Instant now) {
		return Duration.between(saga.getStepStartedAt(), now).toSeconds();
	}
}
