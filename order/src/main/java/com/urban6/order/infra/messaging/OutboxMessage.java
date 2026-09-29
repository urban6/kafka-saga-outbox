package com.urban6.order.infra.messaging;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/** 애플리케이션은 INSERT 만 한다. 발행 진행은 커넥터 오프셋이 안다. */
@Entity
@Table(name = "outbox")
public class OutboxMessage implements Persistable<UUID> {

	/** EventEnvelope.eventId 와 같은 값. 컨슈머 멱등 키로도 쓰인다. */
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

	/** 커넥터의 route.by.field 대상. */
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

	public static OutboxMessage of(UUID eventId, String aggregateType, String aggregateId,
			EventType eventType, String payload) {
		return new OutboxMessage(eventId, aggregateType, aggregateId, eventType, payload);
	}

	@Override
	public UUID getId() {
		return id;
	}

	// PK 직접 할당이라 없으면 save() 가 merge() 로 가 SELECT 가 더 나간다.
	@Transient
	private boolean isNew = true;

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostPersist
	@PostLoad
	void markNotNew() {
		this.isNew = false;
	}
}
