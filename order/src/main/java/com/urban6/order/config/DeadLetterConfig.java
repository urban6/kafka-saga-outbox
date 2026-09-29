package com.urban6.order.config;

import com.urban6.order.infra.messaging.InboundEnvelope;
import com.urban6.order.infra.messaging.Topics;
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

/** DLT 발행 경로. 묶을 트랜잭션이 이미 롤백된 뒤라 Outbox 대상이 아니다. */
@Configuration
public class DeadLetterConfig {

	/** value 가 byte[] 와 InboundEnvelope 둘이라 타입별 위임. 예상 밖 타입은 base64 대신 터진다. */
	@Bean
	public ProducerFactory<String, Object> deadLetterProducerFactory(
			KafkaProperties kafkaProperties, JsonMapper jsonMapper) {

		var valueSerializer = new DelegatingByTypeSerializer(Map.of(
				byte[].class, new ByteArraySerializer(),
				InboundEnvelope.class, new JacksonJsonSerializer<>(jsonMapper)));

		return new DefaultKafkaProducerFactory<>(
				kafkaProperties.buildProducerProperties(),
				new StringSerializer(),
				valueSerializer);
	}

	@Bean
	public KafkaTemplate<String, Object> deadLetterKafkaTemplate(
			ProducerFactory<String, Object> deadLetterProducerFactory) {
		return new KafkaTemplate<>(deadLetterProducerFactory);
	}

	/** 파티션을 원본과 같은 번호로 줘 키 정렬을 유지한다. 그래서 DLT 도 파티션이 3개여야 한다. */
	@Bean
	public DeadLetterPublishingRecoverer deadLetterRecoverer(
			KafkaTemplate<String, Object> deadLetterKafkaTemplate) {

		return new DeadLetterPublishingRecoverer(deadLetterKafkaTemplate,
				(record, exception) ->
						new TopicPartition(record.topic() + Topics.DLT_SUFFIX, record.partition()));
	}
}
