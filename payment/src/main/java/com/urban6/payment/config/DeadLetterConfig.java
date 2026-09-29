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

/** 이 서비스 유일의 프로듀서. DLT 레코드는 함께 묶일 트랜잭션이 이미 롤백된 뒤라 Outbox 대상이 아니다. */
@Configuration
public class DeadLetterConfig {

	/**
	 * DLT value 가 byte[](역직렬화 실패)와 InboundEnvelope 둘이라 타입별로 위임한다.
	 * assignable=false 라 예상 밖 타입은 조용히 base64 가 되는 대신 터진다.
	 */
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

	/** 이름은 우리가 고정하고, 파티션은 원본과 같은 번호를 줘 키 정렬을 유지한다(DLT 도 3파티션). */
	@Bean
	public DeadLetterPublishingRecoverer deadLetterRecoverer(
			KafkaTemplate<String, Object> deadLetterKafkaTemplate) {

		return new DeadLetterPublishingRecoverer(deadLetterKafkaTemplate,
				(record, exception) ->
						new TopicPartition(record.topic() + Topics.DLT_SUFFIX, record.partition()));
	}
}
