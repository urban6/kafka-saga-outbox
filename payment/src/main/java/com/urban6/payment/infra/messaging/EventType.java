package com.urban6.payment.infra.messaging;

/**
 * payment 가 발행하는 회신 종류. 전부 단일 회신 토픽으로 나간다.
 *
 * 이름이 그대로 outbox.event_type 과 카프카 헤더에 실리는 와이어 값이므로 함부로 바꾸지 않는다.
 */
public enum EventType {

	PAYMENT_APPROVED(Topics.ORDER_SAGA_REPLIES),
	PAYMENT_REJECTED(Topics.ORDER_SAGA_REPLIES);

	private final String topic;

	EventType(String topic) {
		this.topic = topic;
	}

	public String topic() {
		return topic;
	}
}
