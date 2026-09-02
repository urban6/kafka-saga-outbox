package com.urban6.payment.config;

import com.urban6.payment.infra.messaging.IdempotencyGuard;
import com.urban6.payment.infra.messaging.OutboxRepository;
import com.urban6.payment.infra.messaging.OutboxWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

/**
 * 메시징 인프라 빈 등록.
 * <p>
 * {@code infra.messaging} 클래스들에 {@code @Component} 를 붙이지 않은 이유는, 무엇을 쓸지를
 * 인프라가 아니라 서비스가 결정하게 두기 위해서다. payment 는 수신·발행을 다 하므로 둘 다 등록한다.
 */
@Configuration
public class MessagingConfig {

	@Bean
	public OutboxWriter outboxWriter(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
		return new OutboxWriter(outboxRepository, objectMapper);
	}

	@Bean
	public IdempotencyGuard idempotencyGuard(JdbcTemplate jdbcTemplate) {
		return new IdempotencyGuard(jdbcTemplate);
	}
}
