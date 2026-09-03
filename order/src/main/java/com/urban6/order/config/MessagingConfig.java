package com.urban6.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.urban6.order.infra.messaging.IdempotencyGuard;
import com.urban6.order.infra.messaging.OutboxRepository;
import com.urban6.order.infra.messaging.OutboxWriter;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

/** 메시징 인프라 빈 등록. order 는 커맨드 발행과 회신 수신을 둘 다 하므로 둘 다 등록한다. */
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
