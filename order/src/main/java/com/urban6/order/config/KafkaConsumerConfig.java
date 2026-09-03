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

/**
 * 회신 컨슈머의 역직렬화 파이프라인.
 * 커스텀 JsonMapper 빈을 만들지 않는다 — 그러면 Boot 의 ObjectMapper 오토컨피그가 물러나
 * OutboxWriter 의 직렬화 규칙까지 딸려 바뀐다.
 */
@Configuration
public class KafkaConsumerConfig {


	/** ErrorHandlingDeserializer 로 감싼다. 안 감싸면 깨진 메시지 하나가 파티션을 통째로 멈춘다. */
	@Bean
	public ConsumerFactory<String, InboundEnvelope> sagaReplyConsumerFactory(
			KafkaProperties kafkaProperties, JsonMapper jsonMapper) {

		// 3번째 인자(useHeadersIfPresent)를 false 로 둬야 spring 타입 헤더를 찾지 않는다.
		// Debezium 이 보낸 메시지에는 그 헤더가 없다.
		var delegate = new JacksonJsonDeserializer<>(InboundEnvelope.class, jsonMapper, false);

		return new DefaultKafkaConsumerFactory<>(
				kafkaProperties.buildConsumerProperties(),
				new StringDeserializer(),                       // 키 = orderNo
				new ErrorHandlingDeserializer<>(delegate));
	}

	/**
	 * 파티션이 3개라 concurrency 도 3. 에러 핸들러는 1초 간격 3회 재시도 뒤 DLT 로 넘긴다.
	 * recoverer 를 안 주면 기본 동작이 로그 한 줄이라 메시지가 본문째 사라진다.
	 * 역직렬화 예외는 재시도 대상이 아니라 첫 실패에서 곧장 DLT 로 간다.
	 */
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
