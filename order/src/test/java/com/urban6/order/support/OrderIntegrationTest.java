package com.urban6.order.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 리스너를 끄고 유스케이스를 직접 부르는 통합 테스트 기반. */
@Tag("integration")
@SpringBootTest(properties = {
		"spring.kafka.listener.auto-startup=false",
		// 배치가 끼어들면 단언이 흔들린다. 사실상 꺼둔다.
		"saga.stuck.scan-interval=1h",
		"retention.scan-interval=1h",
})
public abstract class OrderIntegrationTest {

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		OrderMySqlContainer.registerTo(registry);
	}

	@Autowired
	protected JdbcTemplate jdbcTemplate;

	/** @Transactional 롤백 대신 직접 되돌린다 — 바깥 트랜잭션은 유스케이스의 트랜잭션 경계를 가린다. */
	@BeforeEach
	void resetDatabase() {
		jdbcTemplate.execute("delete from outbox");
		jdbcTemplate.execute("delete from consumed_message");
		jdbcTemplate.execute("delete from api_idempotency");
		jdbcTemplate.execute("delete from saga_instance");
		jdbcTemplate.execute("delete from order_item");
		jdbcTemplate.execute("delete from orders");
		jdbcTemplate.update("update product set reserved_quantity = 0, total_quantity = ? where product_id in (?, ?)",
				100, "P-1001", "P-1002");
		jdbcTemplate.update("update product set reserved_quantity = 0, total_quantity = ? where product_id = ?",
				2, "P-1003");
	}

	protected int countOf(String table) {
		Integer count = jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
		return count == null ? 0 : count;
	}

	protected int reservedOf(String productId) {
		Integer reserved = jdbcTemplate.queryForObject(
				"select reserved_quantity from product where product_id = ?", Integer.class, productId);
		return reserved == null ? 0 : reserved;
	}

	protected int totalOf(String productId) {
		Integer total = jdbcTemplate.queryForObject(
				"select total_quantity from product where product_id = ?", Integer.class, productId);
		return total == null ? 0 : total;
	}
}
