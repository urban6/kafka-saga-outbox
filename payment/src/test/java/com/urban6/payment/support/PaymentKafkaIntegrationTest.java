package com.urban6.payment.support;

import com.urban6.payment.infra.messaging.Topics;
import com.urban6.payment.mockpg.MockPgFaults;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * payment 리스너를 실제로 띄우고 진짜 브로커로 커맨드를 밀어넣는 기반.
 *
 * PaymentIntegrationTest 는 리스너를 끄고 유스케이스를 직접 부른다. 여기서 보는 건 그 앞단이다 —
 * 봉투 역직렬화, 모르는 커맨드, poison pill. 메시지가 브로커를 거쳐야 재현되는 것들이다.
 *
 * 포트가 18083 인 이유: 스프링이 컨텍스트를 캐시해서 PaymentIntegrationTest 쪽(18082)이
 * 살아 있는 채로 이 컨텍스트가 뜬다. 같은 포트를 쓰면 뒤에 뜨는 쪽이 기동에 실패한다.
 */
@Tag("integration")
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
		properties = {
				// 18082 는 리스너 없는 컨텍스트가 쓰고 있다. 컨텍스트 캐시 때문에 둘이 동시에 산다.
				"server.port=18083",
				"pg.base-url=http://localhost:18083",
				"pg.read-timeout=500ms",
				// 이 기반의 존재 이유. 리스너가 떠 있어야 한다.
				"spring.kafka.listener.auto-startup=true",
				"payment.in-doubt.scan-interval=1h",
				"retention.scan-interval=1h",
		})
public abstract class PaymentKafkaIntegrationTest {

	private static final KafkaContainer KAFKA =
			new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));

	private static final KafkaProducer<String, String> PRODUCER;

	/** DLT 도착 대기 상한. 재시도 소진(2초 x 5 = 10초)에 컨슈머 그룹 합류까지 넉넉히 덮는다. */
	private static final Duration DLT_TIMEOUT = Duration.ofSeconds(40);

	static {
		KAFKA.start();
		try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
			// 파티션 3개는 운영과 같다. 같은 키를 쓴 메시지들이 같은 파티션에 실려야
			// "앞 메시지가 뒤 메시지를 막지 않는다" 가 성립한다.
			admin.createTopics(List.of(
					new NewTopic(Topics.PAYMENT_COMMANDS, 3, (short) 1),
					new NewTopic(Topics.PAYMENT_COMMANDS_DLT, 3, (short) 1),
					new NewTopic(Topics.ORDER_SAGA_REPLIES, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException("failed to create test topics", e);
		}
		PRODUCER = new KafkaProducer<>(Map.of(
				ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
				ProducerConfig.ACKS_CONFIG, "all"), new StringSerializer(), new StringSerializer());
	}

	@DynamicPropertySource
	static void containers(DynamicPropertyRegistry registry) {
		PaymentMySqlContainer.registerTo(registry);
		registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
	}

	@AfterAll
	static void flushProducer() {
		PRODUCER.flush();
	}

	@Autowired
	protected JdbcTemplate jdbcTemplate;

	@Autowired
	protected MockPgFaults faults;

	@BeforeEach
	void resetDatabaseAndFaults() {
		jdbcTemplate.execute("delete from outbox");
		jdbcTemplate.execute("delete from consumed_message");
		jdbcTemplate.execute("delete from payment");
		jdbcTemplate.execute("delete from billing_key");
		faults.setRejectRate(0);
		faults.setErrorRate(0);
		faults.setDelayRate(0);
		faults.setDelay(Duration.ZERO);
	}

	/**
	 * 원시 문자열을 그대로 보낸다. 깨진 JSON 을 보낼 수 있어야 poison pill 을 재현하므로
	 * KafkaTemplate 이나 봉투 객체를 쓰지 않는다. 키는 운영과 같은 orderNo 다.
	 */
	protected void publishCommand(String orderNo, String json) {
		try {
			PRODUCER.send(new ProducerRecord<>(Topics.PAYMENT_COMMANDS, orderNo, json)).get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException("failed to publish test command", e);
		}
	}

	/**
	 * 행이 아직 없으면 null 을 준다. queryForObject 를 쓰지 않는 이유가 이거다 —
	 * 비동기라 폴링 중에는 행이 없는 게 정상인데, 예외를 던지면 awaitility 가 그 자리에서 실패한다.
	 */
	protected String columnOf(String column, String orderNo) {
		return jdbcTemplate.queryForList(
				"select " + column + " from payment where order_no = ?", String.class, orderNo)
				.stream().findFirst().orElse(null);
	}

	/**
	 * 전역 카운트를 쓰지 않는다. 리스너가 비동기라 앞 테스트의 미처리 메시지가
	 * @BeforeEach 정리 직후 도착할 수 있고, 그러면 전역 합계가 흔들린다.
	 * 주문번호는 테스트마다 새로 만들므로 이 범위 안에서는 결정적이다.
	 */
	protected int countForOrder(String table, String orderNo) {
		String column = "outbox".equals(table) ? "aggregate_id" : "order_no";
		Integer count = jdbcTemplate.queryForObject(
				"select count(*) from " + table + " where " + column + " = ?", Integer.class, orderNo);
		return count == null ? 0 : count;
	}

	/**
	 * DLT 에 도착한 커맨드를 orderNo 키로 찾아 돌려준다.
	 *
	 * 다른 단언은 전부 JDBC 로 한다 — 리스너가 DB 를 바꾸는 게 관측점이기 때문이다. 그런데 DLT 는
	 * 종착지가 토픽이라 DB 에 아무 흔적이 없다. 오히려 그게 핵심이다: 재시도를 소진한 커맨드는
	 * payment 행도 멱등 선점도 남기지 않는다. DLT 가 유일한 흔적이다.
	 *
	 * awaitility 를 쓰지 않는다. KafkaConsumer 는 스레드 안전하지 않은데 awaitility 는
	 * 단언을 별도 스레드에서 돌린다. 폴링 루프를 직접 도는 편이 안전하다.
	 */
	protected ConsumerRecord<String, String> awaitDltRecord(String orderNo) {
		Map<String, Object> props = Map.of(
				ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
				ConsumerConfig.GROUP_ID_CONFIG, "dlt-probe-" + UUID.randomUUID(),
				ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
				ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

		try (KafkaConsumer<String, String> consumer =
				 new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {

			consumer.subscribe(List.of(Topics.PAYMENT_COMMANDS_DLT));

			Instant deadline = Instant.now().plus(DLT_TIMEOUT);
			while (Instant.now().isBefore(deadline)) {
				for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
					if (orderNo.equals(record.key())) {
						return record;
					}
				}
			}
		}
		throw new AssertionError("no DLT record arrived for orderNo=" + orderNo);
	}

	/** DLT 헤더 읽기. 재생할지 사람이 볼지를 이걸로 가른다. */
	protected static String headerOf(ConsumerRecord<String, String> record, String name) {
		Header header = record.headers().lastHeader(name);
		return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
	}
}
