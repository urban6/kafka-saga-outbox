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
 * {@code outbox} 테이블 매핑. Debezium Outbox Event Router 가 읽어가는 형태다.
 * <p>
 * 도메인 변경과 같은 로컬 트랜잭션에서 이 행을 INSERT 하면, Debezium 이 binlog 에서 그 INSERT 를
 * 집어 카프카로 라우팅한다. 그래서 "DB 는 커밋됐는데 메시지는 안 나갔다"가 생기지 않는다(at-least-once).
 * <p>
 * 애플리케이션은 이 테이블을 <b>쓰기만</b> 한다. 발행 상태(status/retry_count/sent_at)를 두지 않는 이유는
 * 발행 진행을 추적하는 주체가 애플리케이션이 아니라 커넥터의 binlog 오프셋이기 때문이다.
 */
@Entity
@Table(name = "outbox")
public class OutboxMessage {

	/**
	 * 이벤트 고유 식별자. {@code EventEnvelope.eventId} 와 같은 값이며 컨슈머 멱등 키
	 * ({@code consumed_message.message_id})로도 쓰인다.
	 */
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

	/** 발행될 토픽. 커넥터의 {@code route.by.field} 대상이다. */
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
	 * 발행할 행을 만든다. 토픽은 {@link EventType} 이 고정으로 들고 있는 값을 그대로 쓴다.
	 *
	 * @param eventId 발행할 EventEnvelope 의 eventId 와 반드시 같은 값
	 * @param payload 직렬화된 EventEnvelope JSON
	 */
	public static OutboxMessage of(UUID eventId, String aggregateType, String aggregateId,
			EventType eventType, String payload) {
		return new OutboxMessage(eventId, aggregateType, aggregateId, eventType, payload);
	}

	public UUID getId() {
		return id;
	}

	public String getAggregateType() {
		return aggregateType;
	}

	public String getAggregateId() {
		return aggregateId;
	}

	public EventType getEventType() {
		return eventType;
	}

	public String getTopic() {
		return topic;
	}

	public String getPayload() {
		return payload;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
