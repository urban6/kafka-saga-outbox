package com.urban6.payment.infra.messaging;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * outbox 테이블 매핑. Debezium Outbox Event Router 가 binlog 에서 읽어가는 형태다.
 * 애플리케이션은 INSERT 만 한다 — 발행 진행을 아는 주체는 우리가 아니라 커넥터의 오프셋이다.
 */
@Entity
@Table(name = "outbox")
public class OutboxMessage {

	/** EventEnvelope.eventId 와 같은 값이며 컨슈머 멱등 키(consumed_message.message_id)로도 쓰인다. */
	@Id
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "id", nullable = false, length = 36)
	private UUID id;

	/** 애그리거트 종류. Debezium 라우팅 메타데이터로 헤더에 실린다. */
	@Column(name = "aggregate_type", nullable = false, length = 64)
	private String aggregateType;

	/** 애그리거트 식별자(주문이면 orderNo). 라우터가 이 값을 카프카 메시지 키로 쓴다. */
	@Column(name = "aggregate_id", nullable = false, length = 64)
	private String aggregateId;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, length = 100)
	private EventType eventType;

	/** 발행될 토픽. 커넥터의 route.by.field 대상이다. */
	@Column(nullable = false, length = 100)
	private String topic;

	/** 직렬화된 EventEnvelope 본문(JSON). */
	@Column(nullable = false, columnDefinition = "json")
	private String payload;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected OutboxMessage() {
	}

	private OutboxMessage(UUID id, String aggregateType, String aggregateId,
			EventType eventType, String payload) {
		this.id = id;
		this.aggregateType = aggregateType;
		this.aggregateId = aggregateId;
		this.eventType = eventType;
		this.topic = eventType.topic();
		this.payload = payload;
		this.createdAt = Instant.now();
	}

	/**
	 * 발행할 행을 만든다. 토픽은 EventType 이 들고 있는 값을 그대로 쓴다.
	 *
	 * @param eventId 발행할 EventEnvelope 의 eventId 와 반드시 같은 값
	 */
	public static OutboxMessage of(UUID eventId, String aggregateType, String aggregateId,
			EventType eventType, String payload) {
		return new OutboxMessage(eventId, aggregateType, aggregateId, eventType, payload);
	}

	public UUID getId() {
		return id;
	}
}
