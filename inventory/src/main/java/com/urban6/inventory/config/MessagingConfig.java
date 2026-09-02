package com.urban6.inventory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.urban6.inventory.infra.messaging.IdempotencyGuard;
import com.urban6.inventory.infra.messaging.OutboxRepository;
import com.urban6.inventory.infra.messaging.OutboxWriter;

import tools.jackson.databind.ObjectMapper;

/**
 * 메시징 인프라 빈 등록.
 * <p>
 * {@code infra.messaging} 클래스들에 {@code @Component} 를 붙이지 않은 이유는, 무엇을 쓸지를
 * 인프라가 아니라 서비스가 결정하게 두기 위해서다. order 는 컨슈머가 없어 IdempotencyGuard 를
 * 등록하지 않고, inventory 는 둘 다 등록한다.
 */
@Configuration
public class MessagingConfig {

	/**
	 * @param objectMapper Boot 오토컨피그가 만든 Jackson 3 인스턴스.
	 *                     날짜가 기본적으로 ISO-8601 문자열이라 별도 설정이 필요 없다.
	 */
	@Bean
	public OutboxWriter outboxWriter(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
		return new OutboxWriter(outboxRepository, objectMapper);
	}

	@Bean
	public IdempotencyGuard idempotencyGuard(JdbcTemplate jdbcTemplate) {
		return new IdempotencyGuard(jdbcTemplate);
	}
}
