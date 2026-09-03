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
 * 재시도를 소진한 레코드를 DLT 로 옮기는 발행 경로.
 *
 * 이 서비스 유일의 프로듀서다. 회신은 전부 outbox INSERT → Debezium 으로 나가는데
 * 여기만 KafkaTemplate 을 쓴다. Outbox 규칙을 깨는 게 아니라 적용 대상이 아니어서다 —
 * Outbox 의 목적은 비즈니스 트랜잭션과 메시지의 원자성인데, DLT 레코드에는 함께 묶일 트랜잭션이
 * 존재하지 않는다. 그 트랜잭션은 이미 롤백된 뒤다. DLT 는 도메인 메시지가 아니라 운영 산출물이다.
 *
 * MessagingConfig 에 섞지 않고 파일을 따로 두는 이유도 같다 — "발행은 Outbox 뿐" 이라는
 * 읽기를 흐리지 않으려면 이 예외가 자기 자리에 있어야 한다.
 *
 * DLT 를 자동으로 재처리하지 않는다. @KafkaListener 로 되밀면 원인이 안 고쳐진 경우
 * 무한 순환이다. StuckSagaDetector 에 자동 조치를 안 붙인 것과 같은 판단이고, 재생은
 * 사람이 헤더의 예외를 보고 결정한다.
 */
@Configuration
public class DeadLetterConfig {

	/**
	 * DLT 로 실려 가는 value 는 실패 원인에 따라 둘이다.
	 *   - byte[] — 역직렬화 실패. ErrorHandlingDeserializer 가 헤더에 보관해둔 원본
	 *       바이트를 recoverer 가 꺼내 싣는다. 깨진 JSON 을 눈으로 보려면 원문 그대로여야 한다
	 *   - InboundEnvelope — 역직렬화는 됐고 리스너에서 터진 경우
	 * Boot 기본 serializer 는 StringSerializer 라 둘 다 못 보낸다. 메시지를 잃지 않으려고
	 * 넣은 장치가 여기서 조용히 실패하면 최악이므로 타입별로 가른다.
	 *
	 * assignable 기본값(false)을 쓴다 — 정확 매칭이다. Object.class 로 뭉뚱그리면
	 * 예상 밖의 타입이 base64 JSON 으로 조용히 실린다. 위 둘이 전수이므로 그 외에는 터지는 게 맞다.
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
	 * 목적지를 직접 정한다. 기본 resolver 에 맡기지 않는 이유는 그 기본값이 버전마다 다르기 때문이다 —
	 * spring-kafka 4 는 -dlt, 그전엔 .DLT 였다. DLT 이름은 운영이 알고 있어야 하는
	 * 계약이라 프레임워크 업그레이드가 조용히 바꾸면 안 된다(Topics.DLT_SUFFIX).
	 *
	 * 파티션은 원본과 같은 번호를 준다. 그래야 order_no 키의 파티션 정렬이 DLT 에서도
	 * 유지돼 한 주문의 실패들이 순서대로 모인다. 그래서 DLT 도 파티션이 3개여야 한다
	 * (없으면 verifyPartition 기본값이 아무 파티션으로 떨어뜨리고, 그때 순서 근거가 사라진다).
	 *
	 * 헤더에 원본 토픽·파티션·오프셋과 예외 FQCN·메시지·스택이 실린다. 재생할지 사람이 볼지는 이걸로 가른다.
	 *
	 * 예외 헤더를 두 개 봐야 한다. 역직렬화 실패는 kafka_dlt-exception-fqcn 에
	 * DeserializationException 이 그대로 오지만, 리스너 예외는 컨테이너가
	 * ListenerExecutionFailedException 으로 감싸므로 진짜 원인이
	 * kafka_dlt-exception-cause-fqcn 에 들어간다. 앞의 것만 보면 분류가 조용히 틀린다.
	 */
	@Bean
	public DeadLetterPublishingRecoverer deadLetterRecoverer(
			KafkaTemplate<String, Object> deadLetterKafkaTemplate) {

		return new DeadLetterPublishingRecoverer(deadLetterKafkaTemplate,
				(record, exception) ->
						new TopicPartition(record.topic() + Topics.DLT_SUFFIX, record.partition()));
	}
}
