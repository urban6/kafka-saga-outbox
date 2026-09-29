package com.urban6.order.infra.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class EventTypeTest {

	@ParameterizedTest
	@EnumSource(EventType.class)
	@DisplayName("자기 이름으로 왕복한다 — name() 이 곧 와이어 값이다")
	void roundTripsThroughItsOwnName(EventType eventType) {
		assertThat(EventType.fromWire(eventType.name())).contains(eventType);
	}

	@Test
	@DisplayName("모르는 값은 빈 Optional 이다 (예외가 아니다)")
	void unknownValueYieldsEmpty() {
		assertThat(EventType.fromWire("PAYMENT_PARTIALLY_REFUNDED")).isEmpty();
	}

	@Test
	@DisplayName("null 도 빈 Optional 이다 — 헤더가 없는 메시지에서 실제로 들어온다")
	void nullYieldsEmpty() {
		assertThat(EventType.fromWire(null)).isEmpty();
	}

	@Test
	@DisplayName("대소문자가 다르면 모르는 값이다 — 와이어 값은 정확히 일치해야 한다")
	void isCaseSensitive() {
		assertThat(EventType.fromWire("payment_approved")).isEmpty();
	}

	@Test
	@DisplayName("회신 토픽 타입만 isSagaReply 다 — 커맨드가 회신 토픽에 섞여 와도 걸러낸다")
	void onlyReplyTopicTypesAreSagaReplies() {
		assertThat(EventType.PAYMENT_APPROVED.isSagaReply()).isTrue();
		assertThat(EventType.PAYMENT_REJECTED.isSagaReply()).isTrue();

		assertThat(EventType.APPROVE_PAYMENT.isSagaReply()).isFalse();
		assertThat(EventType.ORDER_COMPLETED.isSagaReply()).isFalse();
		assertThat(EventType.ORDER_CANCELED.isSagaReply()).isFalse();
	}

	@Test
	@DisplayName("원격 보상 어휘는 없다 — 값만 두면 없는 기능이 있는 것처럼 읽힌다")
	void hasNoRemoteCompensationVocabulary() {
		assertThat(EventType.fromWire("CANCEL_PAYMENT")).isEmpty();
		assertThat(EventType.fromWire("PAYMENT_CANCELED")).isEmpty();
	}

	@Test
	@DisplayName("도메인 이벤트는 order.events 로, 커맨드는 payment.commands 로 간다")
	void topicRoutingIsCarriedByTheEnum() {
		assertThat(EventType.ORDER_COMPLETED.topic()).isEqualTo(Topics.ORDER_EVENTS);
		assertThat(EventType.ORDER_CANCELED.topic()).isEqualTo(Topics.ORDER_EVENTS);
		assertThat(EventType.APPROVE_PAYMENT.topic()).isEqualTo(Topics.PAYMENT_COMMANDS);
	}
}
