package com.urban6.order.infra.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 와이어 문자열 → enum 변환이 <b>절대 예외를 던지지 않는다</b>는 것을 지킨다.
 * <p>
 * 여기서 {@code valueOf} 를 쓰면 payment 가 회신 종류를 하나 추가하는 순간 그 메시지마다 예외가 나고,
 * 에러 핸들러가 포기할 때까지 같은 파티션의 뒷 메시지가 밀린다. 영구 차단은 아니지만
 * <b>실패의 범위가 메시지 하나가 아니라는</b> 성질은 같다 — 같은 파티션에 실린 다른 주문들이 함께 늦는다.
 * (영구 차단은 역직렬화 실패 쪽이고, {@code SagaReplyListenerIntegrationTest} 가 실측한다)
 */
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
		// pivot 이 PG 청구 성공이라 그 이후는 재시도로 밀고, 그 이전 실패는 로컬 보상으로 끝난다.
		// 원격 보상 커맨드를 보낼 자리가 없으므로 와이어 계약에도 두지 않는다.
		assertThat(EventType.fromWire("CANCEL_PAYMENT")).isEmpty();
		assertThat(EventType.fromWire("PAYMENT_CANCELED")).isEmpty();
	}

	@Test
	@DisplayName("도메인 이벤트는 order.events 로, 커맨드는 payment.commands 로 간다")
	void topicRoutingIsCarriedByTheEnum() {
		// outbox 하나가 토픽 둘로 갈라지는 근거. 라우팅 분기가 코드에 따로 없는 이유다.
		assertThat(EventType.ORDER_COMPLETED.topic()).isEqualTo(Topics.ORDER_EVENTS);
		assertThat(EventType.ORDER_CANCELED.topic()).isEqualTo(Topics.ORDER_EVENTS);
		assertThat(EventType.APPROVE_PAYMENT.topic()).isEqualTo(Topics.PAYMENT_COMMANDS);
	}
}
