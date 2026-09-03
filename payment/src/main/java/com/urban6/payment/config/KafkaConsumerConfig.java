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

/**
 * 커맨드 컨슈머의 역직렬화 파이프라인. yaml 이 아니라 자바인 것은 대상 타입을 못 박아
 * 넘기기 위해서다 — 프로퍼티 방식은 메시지의 타입 헤더에 의존하는데 Debezium 은 그걸 안 보낸다.
 *
 * 커스텀 JsonMapper 빈을 만들지 않는다 — 그러면 Boot 의 ObjectMapper 오토컨피그가 물러나
 * OutboxWriter 의 직렬화 규칙까지 딸려 바뀐다.
 */
@Configuration
public class KafkaConsumerConfig {

	/** @KafkaListener(containerFactory = ...) 에서 문자열을 직접 쓰지 않으려고 상수로 둔다. */

	/**
	 * ErrorHandlingDeserializer 로 감싼다. 깨진 JSON 은 값이 null 이 되고 예외가 헤더에 실려
	 * 그 레코드만 건너뛴다. 안 감싸면 하나가 파티션을 통째로 멈춘다.
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
	 * 파티션이 3개라 concurrency 도 3. 에러 핸들러는 2초 간격 5회 재시도 뒤 DLT 로 넘긴다 —
	 * RETRYABLE 이 실제 경로라 예산을 늘렸다. 역직렬화 예외는 재시도 없이 곧장 DLT 로 간다.
	 */
	@Bean(PaymentCommandListener.CONTAINER_FACTORY)
	public ConcurrentKafkaListenerContainerFactory<String, InboundEnvelope> paymentCommandListenerContainerFactory(
			ConsumerFactory<String, InboundEnvelope> paymentCommandConsumerFactory,
			DeadLetterPublishingRecoverer deadLetterRecoverer) {

		var factory = new ConcurrentKafkaListenerContainerFactory<String, InboundEnvelope>();
		factory.setConsumerFactory(paymentCommandConsumerFactory);
		factory.setConcurrency(3);
		// RETRYABLE 이 실제 경로가 됐다. 1초x3(총 4초)은 PG 가 잠깐만 흔들려도 커맨드를 버린다.
		// 2초x5(총 10초)로 짧은 blip 은 흡수하고, 그 이상은 Stuck 탐지에 맡긴다 —
		// 여기서 더 늘리면 그 파티션의 뒷 주문이 그만큼 밀린다.
		//
		// 소진된 커맨드는 버리지 않고 DLT 로 넘긴다. PG 장애가 10초보다 길면 이 경로가 실제로 열리는데,
		// 그때 커맨드를 잃으면 주문이 PENDING 에 굳고 고객은 다시 주문하는 수밖에 없다.
		factory.setCommonErrorHandler(
				new DefaultErrorHandler(deadLetterRecoverer, new FixedBackOff(2_000L, 5)));
		return factory;
	}
}
