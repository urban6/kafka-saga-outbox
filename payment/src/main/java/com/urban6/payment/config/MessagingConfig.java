package com.urban6.payment.config;

import com.urban6.payment.infra.messaging.IdempotencyGuard;
import com.urban6.payment.infra.messaging.OutboxRepository;
import com.urban6.payment.infra.messaging.OutboxWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

/** infra.messaging 에 @Component 를 안 붙인다 — 무엇을 쓸지는 서비스가 정한다. */
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
