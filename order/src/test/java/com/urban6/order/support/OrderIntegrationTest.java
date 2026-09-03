package com.urban6.order.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * order 통합 테스트의 공통 기반. 진짜 MySQL 위에서 돈다.
 *
 * 이 프로젝트의 동시성 방어는 전부 조건부 UPDATE 와 유니크 인덱스다 — 반환 행 수, 제약 위반,
 * 롤백 범위 같은 것들이라 목으로는 검증되지 않는다. 단위 테스트가 못 닿는 게 정확히 이 층이다.
 *
 * 컨테이너와 DDL 마운트는 OrderMySqlContainer 가 들고 있다. static 초기화로 한 번만 띄운다
 * (@Testcontainers 를 쓰지 않는 이유) — 클래스마다 재시작하면 MySQL 기동 시간이
 * 테스트 수만큼 곱해진다. 정리는 Ryuk 이 한다.
 */
@Tag("integration")
@SpringBootTest(properties = {
		// 리스너를 띄우지 않는다. 이 테스트는 유스케이스를 직접 부르고 Kafka 는 범위 밖이다.
		// 안 끄면 브로커를 찾느라 로그가 경고로 뒤덮인다.
		"spring.kafka.listener.auto-startup=false",
		// 배치가 테스트 도중 끼어들면 행 수·재고 단언이 흔들린다. 사실상 꺼둔다.
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

	/**
	 * 컨테이너를 공유하므로 테스트마다 상태를 되돌린다.
	 *
	 * 테스트에 @Transactional 을 걸어 롤백시키지 않는다. 여기서 검증하려는 게
	 * 유스케이스 자신의 트랜잭션 경계인데, 테스트가 바깥 트랜잭션을 열면 그 경계가 사라진다 —
	 * 롤백돼야 할 것이 롤백됐는지 알 수 없게 된다.
	 *
	 * 재고는 DDL 시드값으로 되돌린다. 시드가 바뀌면 여기도 바뀌어야 하지만,
	 * 상수를 테스트에 박는 것보다 한 곳에서 되돌리는 편이 낫다.
	 */
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
