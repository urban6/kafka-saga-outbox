package com.urban6.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.urban6.order.infra.messaging.IdempotencyGuard;
import com.urban6.order.infra.messaging.OutboxRepository;
import com.urban6.order.infra.messaging.OutboxWriter;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

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
