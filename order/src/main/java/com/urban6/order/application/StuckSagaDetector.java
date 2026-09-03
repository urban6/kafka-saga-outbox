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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 사가 정체 탐지. <b>탐지만 하고 아무것도 고치지 않는다.</b>
 * <p>
 * Kafka 재시도와 {@code ErrorHandlingDeserializer} 는 <b>예외가 난 실패</b>만 잡는다.
 * Debezium 이 죽어 커맨드가 발행조차 안 되거나 회신이 유실되면 아무 예외도 나지 않고,
 * 주문은 {@code PENDING} 사가는 {@code STARTED} 로 조용히 굳는다. 그걸 보는 유일한 눈이다.
 * <p>
 * 자동 조치를 넣지 않는 이유: 사가 테이블만 봐서는 "발행 실패" 인지 "회신 지연" 인지 구분이 안 된다.
 * 지연인데 커맨드를 재발행하면 이중 결제다. 오탐이 0 인 걸 눈으로 확인한 뒤에나 붙일 수 있다.
 * <p>
 * 락이 없다. 읽고 로그만 찍으므로 인스턴스가 여러 대면 같은 알람이 여러 번 뜰 뿐이다.
 * 조치를 붙이는 순간 {@code FOR UPDATE SKIP LOCKED} 나 ShedLock 이 필요해진다.
 */
@Component
@RequiredArgsConstructor
public class StuckSagaDetector {

	private static final Logger log = LoggerFactory.getLogger(StuckSagaDetector.class);

	/**
	 * 스캔 대상. {@link SagaInstance#isTerminated()} 의 여집합이다.
	 * <p>
	 * {@code COMPENSATING} 은 아직 아무도 쓰지 않지만 넣어둔다. 원격 보상이 붙을 때
	 * 여기 추가하는 걸 잊는 것이 정확히 "보상이 멈췄는데 아무도 모르는" 버그다.
	 * <p>
	 * {@code COMPENSATION_FAILED} 는 종료 상태라 안 걸린다. 그건 정체가 아니라
	 * 이미 포기하고 사람에게 넘긴 큐라서 성격이 다르다.
	 */
	private static final List<SagaStatus> ACTIVE =
			List.of(SagaStatus.STARTED, SagaStatus.COMPENSATING);

	/** 로그 한 줄에 담을 order_no 최대 개수. 나머지는 메트릭으로 본다. */
	private static final int LOG_SAMPLE = 10;

	private final SagaInstanceRepository sagaInstanceRepository;
	private final StuckSagaProperties properties;
	private final MeterRegistry meterRegistry;

	// 초기화된 final 필드라 @RequiredArgsConstructor 가 생성자에 넣지 않는다.
	private final Map<SagaStep, AtomicLong> stuckCount = new EnumMap<>(SagaStep.class);
	private final Map<SagaStep, AtomicLong> oldestAgeSeconds = new EnumMap<>(SagaStep.class);

	/**
	 * 게이지를 <b>모든 단계에 대해 미리</b> 등록한다.
	 * <p>
	 * 값이 없을 때 메트릭이 아예 사라지면 알람 룰이 "데이터 없음" 으로 빠져 울리지 않는다.
	 * "0 이다" 와 "아무 말도 없다" 는 다르다.
	 * <p>
	 * Micrometer 게이지는 대상 객체를 <b>약참조</b>로 잡는다. AtomicLong 을 필드 맵에 붙들고 있지 않으면
	 * GC 된 뒤 게이지가 NaN 을 뱉는다.
	 */
	@PostConstruct
	void registerGauges() {
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

	/**
	 * {@code fixedDelay} 다. 스캔이 늦어지면 다음 스캔도 그만큼 밀린다 —
	 * {@code fixedRate} 로 두면 느려진 스캔이 겹쳐 쌓인다.
	 */
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

	/**
	 * 정체가 없는 단계에도 <b>0 을 쓴다.</b> 건너뛰면 해소된 뒤에도 옛 값이 남아 알람이 안 꺼진다.
	 */
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

	/**
	 * <b>순수 함수.</b> 시각을 주입받아 테스트에서 고정한다.
	 * 경계는 이상(&gt;=)이다 — 임계값 정각도 정체로 본다.
	 */
	boolean isStuck(SagaInstance saga, Instant now) {
		return Duration.between(saga.getStepStartedAt(), now)
				.compareTo(properties.thresholdFor(saga.getCurrentStep())) >= 0;
	}

	private static long ageSeconds(SagaInstance saga, Instant now) {
		return Duration.between(saga.getStepStartedAt(), now).toSeconds();
	}
}
