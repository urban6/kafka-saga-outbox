package com.urban6.payment.config;

import com.urban6.payment.infra.messaging.InboundEnvelope;
import com.urban6.payment.infra.messaging.Topics;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * 재시도를 소진한 레코드를 DLT 로 옮기는 발행 경로. 이 서비스 유일의 프로듀서다.
 *
 * Outbox 규칙의 예외가 아니라 적용 대상이 아니다 — 함께 묶일 트랜잭션이 이미 롤백된 뒤라
 * 원자성을 지킬 대상이 없다. DLT 는 도메인 메시지가 아니라 운영 산출물이다.
 */
@Configuration
public class DeadLetterConfig {

	/**
	 * DLT 에 실리는 value 는 byte[](역직렬화 실패 시 원본 바이트)와 InboundEnvelope(그 외) 둘이라
	 * 타입별로 위임한다. 기본 StringSerializer 로는 둘 다 못 보낸다.
	 * assignable 기본값(false)이라 예상 밖의 타입은 조용히 base64 가 되는 대신 터진다.
	 */
	@Bean
	public ProducerFactory<String, Object> deadLetterProducerFactory(
			KafkaProperties kafkaProperties, JsonMapper jsonMapper) {

		var valueSerializer = new DelegatingByTypeSerializer(Map.of(
				byte[].class, new ByteArraySerializer(),
				InboundEnvelope.class, new JacksonJsonSerializer<>(jsonMapper)));

		return new DefaultKafkaProducerFactory<>(
				kafkaProperties.buildProducerProperties(),
				new StringSerializer(),   // 키 = orderNo. 원본 키를 그대로 옮긴다
				valueSerializer);
	}

	@Bean
	public KafkaTemplate<String, Object> deadLetterKafkaTemplate(
			ProducerFactory<String, Object> deadLetterProducerFactory) {
		return new KafkaTemplate<>(deadLetterProducerFactory);
	}

	/**
	 * DLT 목적지를 직접 정한다. 이름은 우리가 고정하고(Topics.DLT_SUFFIX), 파티션은 원본과
	 * 같은 번호를 줘 order_no 키의 정렬을 DLT 에서도 유지한다. 그래서 DLT 도 파티션이 3개여야 한다.
	 */
	@Bean
	public DeadLetterPublishingRecoverer deadLetterRecoverer(
			KafkaTemplate<String, Object> deadLetterKafkaTemplate) {

		return new DeadLetterPublishingRecoverer(deadLetterKafkaTemplate,
				(record, exception) ->
						new TopicPartition(record.topic() + Topics.DLT_SUFFIX, record.partition()));
	}
}
