package com.urban6.payment.infra.messaging;

/** 이름이 outbox.event_type 과 카프카 헤더에 실리는 와이어 값이다. */
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
