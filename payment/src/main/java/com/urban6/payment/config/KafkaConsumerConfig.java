package com.urban6.payment.config;

import com.urban6.payment.infra.messaging.InboundEnvelope;
import com.urban6.payment.infra.messaging.PaymentCommandListener;
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

/** 커스텀 JsonMapper 빈을 만들지 않는다 — Boot 오토컨피그가 물러나 OutboxWriter 직렬화까지 바뀐다. */
@Configuration
public class KafkaConsumerConfig {

	/** ErrorHandlingDeserializer 로 안 감싸면 깨진 JSON 하나가 파티션을 통째로 멈춘다. */
	@Bean
	public ConsumerFactory<String, InboundEnvelope> paymentCommandConsumerFactory(
			KafkaProperties kafkaProperties, JsonMapper jsonMapper) {

		// useHeadersIfPresent=false: Debezium 메시지에는 spring 타입 헤더가 없다.
		var delegate = new JacksonJsonDeserializer<>(InboundEnvelope.class, jsonMapper, false);

		return new DefaultKafkaConsumerFactory<>(
				kafkaProperties.buildConsumerProperties(),
				new StringDeserializer(),
				new ErrorHandlingDeserializer<>(delegate));
	}

	@Bean(PaymentCommandListener.CONTAINER_FACTORY)
	public ConcurrentKafkaListenerContainerFactory<String, InboundEnvelope> paymentCommandListenerContainerFactory(
			ConsumerFactory<String, InboundEnvelope> paymentCommandConsumerFactory,
			DeadLetterPublishingRecoverer deadLetterRecoverer) {

		var factory = new ConcurrentKafkaListenerContainerFactory<String, InboundEnvelope>();
		factory.setConsumerFactory(paymentCommandConsumerFactory);
		factory.setConcurrency(3);
		// 2초x5 로 짧은 PG blip 을 흡수한다. 더 늘리면 같은 파티션의 뒷 주문이 그만큼 밀린다.
		factory.setCommonErrorHandler(
				new DefaultErrorHandler(deadLetterRecoverer, new FixedBackOff(2_000L, 5)));
		return factory;
	}
}
