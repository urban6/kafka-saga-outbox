package com.urban6.payment.config;

import com.urban6.payment.infra.messaging.InboundEnvelope;
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

import tools.jackson.databind.json.JsonMapper;

/**
 * 커맨드 컨슈머의 역직렬화 파이프라인.
 *
 * yaml 대신 자바로 만드는 이유는 JacksonJsonDeserializer 에 대상 타입을 못 박아
 * 넘겨야 하기 때문이다. 프로퍼티로 지정하는 방식은 메시지의 타입 헤더에 의존한다.
 *
 * 커스텀 JsonMapper 빈을 만들지 않는다. Jackson 3 는 모르는 필드를 기본으로
 * 무시하므로(tolerant reader 가 기본값) 따로 설정할 게 없고, JsonMapper extends ObjectMapper
 * 라서 직접 등록하면 Boot 의 ObjectMapper 오토컨피그가 물러나
 * OutboxWriter 의 직렬화 규칙까지 딸려 바뀐다.
 */
@Configuration
public class KafkaConsumerConfig {

	/** @KafkaListener(containerFactory = ...) 에서 문자열을 직접 쓰지 않으려고 상수로 둔다. */
	public static final String CONTAINER_FACTORY = "paymentCommandListenerContainerFactory";

	/**
	 * 값 역직렬화는 ErrorHandlingDeserializer 로 감싼다(poison pill 방어).
	 *
	 * 깨진 JSON 이 오면 예외를 리스너까지 올리는 대신 값을 null 로 만들고 헤더에 예외를 실어
	 * 에러 핸들러가 그 레코드만 건너뛰게 한다. 감싸지 않으면 역직렬화 예외가 무한 재시도되어
	 * 파티션 하나가 통째로 멈춘다.
	 */
	@Bean
	public ConsumerFactory<String, InboundEnvelope> paymentCommandConsumerFactory(
			KafkaProperties kafkaProperties, JsonMapper jsonMapper) {

		// 세 번째 인자(useHeadersIfPresent)를 false 로 둬야 spring 타입 헤더를 찾지 않고
		// 위에서 지정한 타입으로 바로 읽는다. Debezium 이 보낸 메시지에는 그 헤더가 없다.
		var delegate = new JacksonJsonDeserializer<>(InboundEnvelope.class, jsonMapper, false);

		return new DefaultKafkaConsumerFactory<>(
				kafkaProperties.buildConsumerProperties(),
				new StringDeserializer(),                       // 키 = orderNo
				new ErrorHandlingDeserializer<>(delegate));
	}

	/**
	 * 파티션이 3개라 concurrency 도 3. 더 올려도 컨슈머 하나는 놀게 된다.
	 *
	 * DefaultErrorHandler 는 2초 간격 5회 재시도 후 로그를 남기고 넘어간다.
	 * 역직렬화 예외는 재시도 대상에서 기본 제외된다 — 몇 번을 다시 읽어도 결과가 같기 때문이다.
	 */
	@Bean(CONTAINER_FACTORY)
	public ConcurrentKafkaListenerContainerFactory<String, InboundEnvelope> paymentCommandListenerContainerFactory(
			ConsumerFactory<String, InboundEnvelope> paymentCommandConsumerFactory) {

		var factory = new ConcurrentKafkaListenerContainerFactory<String, InboundEnvelope>();
		factory.setConsumerFactory(paymentCommandConsumerFactory);
		factory.setConcurrency(3);
		// RETRYABLE 이 실제 경로가 됐다. 1초x3(총 4초)은 PG 가 잠깐만 흔들려도 커맨드를 버린다.
		// 2초x5(총 10초)로 짧은 blip 은 흡수하고, 그 이상은 Stuck 탐지에 맡긴다 —
		// 여기서 더 늘리면 그 파티션의 뒷 주문이 그만큼 밀린다.
		factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(2_000L, 5)));
		return factory;
	}
}
