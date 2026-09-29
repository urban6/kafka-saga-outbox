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
		assertThat(OrderSagaOrchestrator.decide(SagaStep.APPROVE_PAYMENT, eventType))
				.isEqualTo(IGNORE);
	}

	@Test
	@DisplayName("전 조합 중 무언가를 하는 건 정확히 2가지뿐이다")
	void onlyTwoCombinationsAct() {
		Map<Boolean, Long> byActing = Arrays.stream(SagaStep.values())
				.flatMap(step -> Arrays.stream(EventType.values())
						.map(type -> OrderSagaOrchestrator.decide(step, type)))
				.collect(Collectors.partitioningBy(decision -> decision != IGNORE, Collectors.counting()));

		assertThat(byActing.get(true)).isEqualTo(2);
		assertThat(byActing.get(false))
				.isEqualTo((long) SagaStep.values().length * EventType.values().length - 2);
	}
}
