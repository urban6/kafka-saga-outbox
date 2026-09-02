package com.urban6.inventory.config;

import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import com.urban6.inventory.infra.messaging.InboundEnvelope;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 컨슈머 역직렬화 파이프라인.
 * <p>
 * yaml 이 아니라 자바로 만드는 이유는 {@link JacksonJsonDeserializer} 에 tolerant reader 설정을 넣은
 * {@link JsonMapper} 를 주입해야 하기 때문이다. 프로퍼티만으로는 그게 안 된다.
 */
@Configuration
public class KafkaConsumerConfig {

	/** {@code @KafkaListener(containerFactory = ...)} 에서 문자열을 직접 쓰지 않으려고 상수로 둔다. */
	public static final String CONTAINER_FACTORY = "inventoryListenerContainerFactory";

	/**
	 * <b>tolerant reader</b> 의 핵심. 모르는 필드를 만나도 실패하지 않는다.
	 * 프로듀서가 봉투나 payload 에 필드를 추가해도 이 컨슈머는 재배포 없이 그대로 돈다.
	 */
	@Bean
	public JsonMapper messageJsonMapper() {
		return JsonMapper.builder()
				.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.build();
	}

	/**
	 * 값 역직렬화는 {@link ErrorHandlingDeserializer} 로 감싼다(poison pill 방어).
	 * <p>
	 * 깨진 JSON 이 오면 예외를 리스너까지 올리는 대신 값을 null 로 만들고 헤더에 예외를 실어
	 * 에러 핸들러가 그 레코드만 건너뛰게 한다. 감싸지 않으면 역직렬화 예외가 무한 재시도되어
	 * <b>파티션 하나가 통째로 멈춘다</b>.
	 */
	@Bean
	public ConsumerFactory<String, InboundEnvelope> inventoryConsumerFactory(
			KafkaProperties kafkaProperties, JsonMapper messageJsonMapper) {

		// Debezium 이 보낸 메시지에는 spring 의 타입 헤더가 없다. 세 번째 인자(useHeadersIfPresent)를
		// false 로 둬야 헤더를 찾지 않고 아래에서 지정한 타입으로 바로 읽는다.
		var delegate = new JacksonJsonDeserializer<>(InboundEnvelope.class, messageJsonMapper, false);

		return new DefaultKafkaConsumerFactory<>(
				kafkaProperties.buildConsumerProperties(),
				new StringDeserializer(),                       // 키 = orderNo
				new ErrorHandlingDeserializer<>(delegate));
	}

	/**
	 * 파티션이 3개라 concurrency 도 3. 그 이상 올려도 컨슈머 하나는 놀게 된다.
	 * <p>
	 * {@link DefaultErrorHandler} 는 1초 간격 3회까지 재시도한 뒤 로그를 남기고 넘어간다.
	 * 역직렬화 예외는 기본적으로 재시도 대상이 아니라서 즉시 건너뛴다 — 재시도해도 결과가 같기 때문이다.
	 */
	@Bean(CONTAINER_FACTORY)
	public ConcurrentKafkaListenerContainerFactory<String, InboundEnvelope> inventoryListenerContainerFactory(
			ConsumerFactory<String, InboundEnvelope> inventoryConsumerFactory) {

		var factory = new ConcurrentKafkaListenerContainerFactory<String, InboundEnvelope>();
		factory.setConsumerFactory(inventoryConsumerFactory);
		factory.setConcurrency(3);
		factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1_000L, 3)));
		return factory;
	}
}
