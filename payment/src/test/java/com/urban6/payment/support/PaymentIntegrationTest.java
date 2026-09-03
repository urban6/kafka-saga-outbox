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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

/**
 * payment 통합 테스트의 공통 기반. 진짜 MySQL + <b>진짜 HTTP</b> 위에서 돈다.
 * <p>
 * Mock PG 가 같은 앱 안에 있지만 빈을 직접 주입하지 않고 실제 소켓으로 부른다 —
 * 운영 배선과 같고, 무엇보다 <b>빈 호출로는 read-timeout 이 재현되지 않는다.</b>
 * 이 클래스가 검증하려는 것의 절반이 "응답을 못 받았을 때 무엇을 하는가" 라서
 * 그 지점을 흉내로 대체하면 테스트가 무의미해진다.
 * <p>
 * 그래서 {@code DEFINED_PORT} 다. 랜덤 포트를 쓰면 {@code pg.base-url} 을 컨텍스트 기동 전에 알 수 없고,
 * {@code RestClient} 빈이 baseUrl 을 고정으로 들고 만들어지므로 나중에 못 바꾼다.
 * <p>
 * {@code read-timeout} 을 500ms 로 줄인다. 운영값 3초는 타임아웃 테스트를 그만큼 느리게만 만든다 —
 * 검증 대상은 "제한 시간이 얼마인가" 가 아니라 "넘겼을 때 무엇을 하는가" 다.
 */
@Tag("integration")
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
		properties = {
				"server.port=18082",
				"pg.base-url=http://localhost:18082",
				"pg.read-timeout=500ms",
				// 리스너는 띄우지 않는다. 유스케이스를 직접 부르고 Kafka 는 범위 밖이다.
				"spring.kafka.listener.auto-startup=false",
				// 배치는 테스트가 직접 부른다. 스케줄러가 끼어들면 행 수 단언이 흔들린다.
				"payment.in-doubt.scan-interval=1h",
				"retention.scan-interval=1h",
		})
public abstract class PaymentIntegrationTest {

	protected static final String PG_BASE_URL = "http://localhost:18082";

	private static final MySQLContainer<?> MYSQL =
			new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
					.withDatabaseName("payment_db")
					// MySQL 8 기본 인증이 caching_sha2_password 라 이게 없으면
					// "RSA public key is not available client side" 로 연결이 끊긴다.
					.withUrlParam("allowPublicKeyRetrieval", "true")
					.withUrlParam("useSSL", "false")
					// DDL 을 복사하지 않는다. 운영과 같은 파일을 그대로 마운트한다.
					.withCopyFileToContainer(
							MountableFile.forHostPath("../docker/mysql/init/03-payment.sql"),
							"/docker-entrypoint-initdb.d/03-payment.sql");

	static {
		MYSQL.start();
	}

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
	}

	@Autowired
	protected JdbcTemplate jdbcTemplate;

	/** 장애 주입 스위치. 테스트가 확률이 아니라 <b>확정</b>으로 켠다 — 확률로는 재현이 안 된다. */
	@Autowired
	protected MockPgFaults faults;

	/**
	 * Mock PG 를 직접 두드리기 위한 클라이언트. 프로덕션 {@code PgClient} 를 쓰지 않는 이유는
	 * 테스트 <b>준비</b>에 검증 대상을 쓰면 무엇이 깨졌는지 구분되지 않기 때문이다.
	 */
	protected final RestClient pg = RestClient.builder().baseUrl(PG_BASE_URL).build();

	@BeforeEach
	void resetDatabaseAndFaults() {
		jdbcTemplate.execute("delete from outbox");
		jdbcTemplate.execute("delete from consumed_message");
		jdbcTemplate.execute("delete from payment");
		jdbcTemplate.execute("delete from billing_key");

		// PG 의 인메모리 상태는 컨텍스트와 함께 살아 있다. 테스트마다 orderNo 를 새로 만들어 피한다.
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
