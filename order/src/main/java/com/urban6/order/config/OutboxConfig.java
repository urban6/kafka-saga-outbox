package com.urban6.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.urban6.order.infra.messaging.OutboxRepository;
import com.urban6.order.infra.messaging.OutboxWriter;

import tools.jackson.databind.ObjectMapper;

/**
 * {@code common:outbox} 의 인프라 컴포넌트를 이 서비스의 빈으로 등록한다.
 * <p>
 * common 모듈 쪽에 {@code @Component} 를 붙이지 않는 이유는, 어떤 서비스가 무엇을 쓸지를
 * 라이브러리가 아니라 서비스가 결정하게 두기 위해서다. order 는 Outbox 발행만 하고
 * 컨슈머가 없으므로 {@code IdempotencyGuard} 는 여기서 등록하지 않는다.
 */
@Configuration
public class OutboxConfig {

	/**
	 * @param objectMapper Boot 가 오토컨피그로 만든 Jackson 3 인스턴스.
	 *                     웹 계층 응답과 같은 직렬화 규칙(Instant → ISO-8601)을 그대로 쓴다.
	 */
	@Bean
	public OutboxWriter outboxWriter(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
		return new OutboxWriter(outboxRepository, objectMapper);
	}
}
