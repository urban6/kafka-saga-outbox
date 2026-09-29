package com.urban6.order.config;

import com.urban6.order.infra.messaging.InboundEnvelope;
import com.urban6.order.infra.messaging.SagaReplyListener;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import tools.jackson.databind.json.JsonMapper;

/** 커스텀 JsonMapper 빈을 만들지 않는다. Boot 의 ObjectMapper 오토컨피그가 물러나 Outbox 직렬화까지 바뀐다. */
@Configuration
public class KafkaConsumerConfig {


	/** ErrorHandlingDeserializer 로 감싼다. 안 감싸면 깨진 메시지 하나가 파티션을 통째로 멈춘다. */
	@Bean
	public ConsumerFactory<String, InboundEnvelope> sagaReplyConsumerFactory(
			KafkaProperties kafkaProperties, JsonMapper jsonMapper) {

		// useHeadersIfPresent=false: Debezium 메시지에는 spring 타입 헤더가 없다.
		var delegate = new JacksonJsonDeserializer<>(InboundEnvelope.class, jsonMapper, false);

		return new DefaultKafkaConsumerFactory<>(
				kafkaProperties.buildConsumerProperties(),
				new StringDeserializer(),
				new ErrorHandlingDeserializer<>(delegate));
	}

	/** recoverer 를 안 주면 재시도를 소진한 메시지가 로그 한 줄만 남기고 사라진다. */
	@Bean(SagaReplyListener.CONTAINER_FACTORY)
	public ConcurrentKafkaListenerContainerFactory<String, InboundEnvelope> sagaReplyListenerContainerFactory(
			ConsumerFactory<String, InboundEnvelope> sagaReplyConsumerFactory,
			DeadLetterPublishingRecoverer deadLetterRecoverer) {

		var factory = new ConcurrentKafkaListenerContainerFactory<String, InboundEnvelope>();
		factory.setConsumerFactory(sagaReplyConsumerFactory);
		factory.setConcurrency(3);
		factory.setCommonErrorHandler(
				new DefaultErrorHandler(deadLetterRecoverer, new FixedBackOff(1_000L, 3)));
		return factory;
	}
}
