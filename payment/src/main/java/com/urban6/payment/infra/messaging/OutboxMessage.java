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

/** 애플리케이션은 INSERT 만 한다. 발행 진행은 커넥터 오프셋만 안다. */
@Entity
@Table(name = "outbox")
public class OutboxMessage {

	@Id
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "id", nullable = false, length = 36)
	private UUID id;

	@Column(name = "aggregate_type", nullable = false, length = 64)
	private String aggregateType;

	/** 라우터가 이 값을 카프카 메시지 키로 쓴다. */
	@Column(name = "aggregate_id", nullable = false, length = 64)
	private String aggregateId;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, length = 100)
	private EventType eventType;

	@Column(nullable = false, length = 100)
	private String topic;

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

	/** @param eventId 발행할 EventEnvelope 의 eventId 와 반드시 같은 값 */
	public static OutboxMessage of(UUID eventId, String aggregateType, String aggregateId,
			EventType eventType, String payload) {
		return new OutboxMessage(eventId, aggregateType, aggregateId, eventType, payload);
	}

	public UUID getId() {
		return id;
	}
}
