package com.urban6.payment.support;

import com.urban6.payment.mockpg.MockPgFaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Mock PG 를 빈 주입이 아니라 실제 HTTP(DEFINED_PORT)로 부른다 — 빈 호출로는 read-timeout 이 재현되지 않는다.
 * read-timeout 500ms: 검증 대상은 제한 시간이 아니라 넘겼을 때의 행동이다.
 */
@Tag("integration")
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
		properties = {
				"server.port=18082",
				"pg.base-url=http://localhost:18082",
				"pg.read-timeout=500ms",
				"spring.kafka.listener.auto-startup=false",
				// 스케줄러가 끼어들면 행 수 단언이 흔들린다.
				"payment.in-doubt.scan-interval=1h",
				"retention.scan-interval=1h",
		})
public abstract class PaymentIntegrationTest {

	protected static final String PG_BASE_URL = "http://localhost:18082";

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		PaymentMySqlContainer.registerTo(registry);
	}

	@Autowired
	protected JdbcTemplate jdbcTemplate;

	/** 확률이 아니라 확정(1.0)으로 켠다. 확률로는 재현이 안 된다. */
	@Autowired
	protected MockPgFaults faults;

	/** 준비에 검증 대상(PgClient)을 쓰지 않는다. */
	protected final RestClient pg = RestClient.builder().baseUrl(PG_BASE_URL).build();

	@BeforeEach
	void resetDatabaseAndFaults() {
		jdbcTemplate.execute("delete from outbox");
		jdbcTemplate.execute("delete from consumed_message");
		jdbcTemplate.execute("delete from payment");
		jdbcTemplate.execute("delete from billing_key");

		// PG 인메모리 상태는 리셋하지 않는다. 테스트마다 새 orderNo 로 피한다.
		faults.setRejectRate(0);
		faults.setErrorRate(0);
		faults.setDelayRate(0);
		faults.setDelay(Duration.ZERO);
	}

	protected int countOf(String table) {
		Integer count = jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
		return count == null ? 0 : count;
	}

	protected String columnOf(String column, String orderNo) {
		return jdbcTemplate.queryForObject(
				"select " + column + " from payment where order_no = ?", String.class, orderNo);
	}

	protected String outboxEventType(String orderNo) {
		return jdbcTemplate.queryForObject(
				"select event_type from outbox where aggregate_id = ?", String.class, orderNo);
	}
}
