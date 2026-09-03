package com.urban6.order.application;

import com.urban6.order.domain.SagaStep;
import com.urban6.order.infra.messaging.EventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static com.urban6.order.application.OrderSagaOrchestrator.SagaDecision.COMPENSATE;
import static com.urban6.order.application.OrderSagaOrchestrator.SagaDecision.COMPLETE;
import static com.urban6.order.application.OrderSagaOrchestrator.SagaDecision.IGNORE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * decide() 는 순수 함수라 전 조합을 전수 검증한다.
 * SagaStep 2 × EventType 7 = 14 가지가 전부다.
 *
 * 이 테스트가 지키는 건 "무엇을 하는가" 가 아니라 "무엇을 안 하는가" 다.
 * 사가에서 사고는 대개 하지 말아야 할 전이를 해서 난다 — 늦게 온 회신, 지나간 단계,
 * 아직 만들지 않은 경로의 회신. 그래서 아래 표는 IGNORE 가 대부분이고 그게 정상이다.
 */
class OrderSagaOrchestratorTest {

	@Test
	@DisplayName("결제 승인 회신 → 완료")
	void approvedLeadsToComplete() {
		assertThat(OrderSagaOrchestrator.decide(SagaStep.APPROVE_PAYMENT, EventType.PAYMENT_APPROVED))
				.isEqualTo(COMPLETE);
	}

	@Test
	@DisplayName("결제 거절 회신 → 보상")
	void rejectedLeadsToCompensate() {
		assertThat(OrderSagaOrchestrator.decide(SagaStep.APPROVE_PAYMENT, EventType.PAYMENT_REJECTED))
				.isEqualTo(COMPENSATE);
	}

	@ParameterizedTest
	@EnumSource(value = EventType.class, names = {"PAYMENT_APPROVED", "PAYMENT_REJECTED"},
			mode = EnumSource.Mode.EXCLUDE)
	@DisplayName("결제 단계에서도 승인/거절 외의 타입은 전부 무시한다")
	void ignoresNonPaymentRepliesWhileWaiting(EventType eventType) {
		// 커맨드 타입(APPROVE_PAYMENT 등)이 회신 토픽에 잘못 실려 와도 여기서 걸린다.
		assertThat(OrderSagaOrchestrator.decide(SagaStep.APPROVE_PAYMENT, eventType))
				.isEqualTo(IGNORE);
	}

	@Test
	@DisplayName("전 조합 중 무언가를 하는 건 정확히 2가지뿐이다")
	void onlyTwoCombinationsAct() {
		// SagaStep 이 하나뿐이라 조합 수는 EventType 수와 같다. 단계가 늘면 이 계산이 자동으로 커진다.
		Map<Boolean, Long> byActing = Arrays.stream(SagaStep.values())
				.flatMap(step -> Arrays.stream(EventType.values())
						.map(type -> OrderSagaOrchestrator.decide(step, type)))
				.collect(Collectors.partitioningBy(decision -> decision != IGNORE, Collectors.counting()));

		assertThat(byActing.get(true)).isEqualTo(2);
		assertThat(byActing.get(false))
				.isEqualTo((long) SagaStep.values().length * EventType.values().length - 2);
	}
}
