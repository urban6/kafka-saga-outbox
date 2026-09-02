package com.urban6.inventory.infra.messaging;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code outbox} 테이블 매핑. Debezium Outbox Event Router 가 읽어가는 형태다.
 * <p>
 * 애플리케이션은 이 테이블을 <b>쓰기만</b> 한다. status/retry_count/sent_at 이 없는 이유는 발행 진행을
 * 추적하는 주체가 애플리케이션이 아니라 커넥터의 binlog 오프셋이기 때문이다. UPDATE 를 하면
 * 커넥터의 {@code table.op.invalid.behavior} 에 걸려 경고와 함께 그 레코드가 버려진다.
 */
@Entity
@Table(name = "outbox")
public class OutboxMessage {

	/** {@code OutboundEnvelope.eventId} 와 같은 값. 컨슈머 멱등 키로도 쓰인다. */
	@Id
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "id", nullable = false, length = 36)
	private UUID id;

	/** 애그리거트 종류. 커넥터가 헤더로 실어 보낸다. */
	@Column(name = "aggregate_type", nullable = false, length = 64)
	private String aggregateType;

	/** 라우터가 카프카 메시지 키로 쓰는 값. 여기서는 orderNo. */
	@Column(name = "aggregate_id", nullable = false, length = 64)
	private String aggregateId;

	/**
	 * order 의 같은 필드와 달리 enum 이 아니라 String 이다. 발행 측이라 enum 을 써도 당장은
	 * 안전하지만, 이 서비스는 타입을 {@link EventTypes} 상수 하나로만 다룬다.
	 */
	@Column(name = "event_type", nullable = false, length = 100)
	private String eventType;

	/** 발행될 토픽. 커넥터의 {@code route.by.field} 대상이다. */
	@Column(nullable = false, length = 100)
	private String topic;

	/** 직렬화된 OutboundEnvelope 본문(JSON). */
	@Column(nullable = false, columnDefinition = "json")
	private String payload;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected OutboxMessage() {
	}

	private OutboxMessage(UUID id, String aggregateType, String aggregateId,
			String eventType, String topic, String payload) {
		this.id = id;
		this.aggregateType = aggregateType;
		this.aggregateId = aggregateId;
		this.eventType = eventType;
		this.topic = topic;
		this.payload = payload;
		this.createdAt = Instant.now();
	}

	public static OutboxMessage of(UUID eventId, String aggregateType, String aggregateId,
			String eventType, String topic, String payload) {
		return new OutboxMessage(eventId, aggregateType, aggregateId, eventType, topic, payload);
	}

	public UUID getId() {
		return id;
	}

	public String getEventType() {
		return eventType;
	}

	public String getTopic() {
		return topic;
	}
}
