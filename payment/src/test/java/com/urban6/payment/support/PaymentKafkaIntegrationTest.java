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
 * 리스너를 띄우고 진짜 브로커로 커맨드를 민다.
 * 포트 18083: 컨텍스트 캐시로 18082 컨텍스트와 동시에 살아서 같은 포트면 기동에 실패한다.
 */
@Tag("integration")
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
		properties = {
				"server.port=18083",
				"pg.base-url=http://localhost:18083",
				"pg.read-timeout=500ms",
				"spring.kafka.listener.auto-startup=true",
				"payment.in-doubt.scan-interval=1h",
				"retention.scan-interval=1h",
		})
public abstract class PaymentKafkaIntegrationTest {

	private static final KafkaContainer KAFKA =
			new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));

	private static final KafkaProducer<String, String> PRODUCER;

	/** 재시도 소진(2초 x 5)과 컨슈머 그룹 합류를 덮는다. */
	private static final Duration DLT_TIMEOUT = Duration.ofSeconds(40);

	static {
		KAFKA.start();
		try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
			// 자동 생성은 1개라 "같은 파티션의 다음 메시지를 막지 않는다" 를 증명할 수 없다.
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

	/** 깨진 JSON 을 보낼 수 있게 원시 문자열로 발행한다. */
	protected void publishCommand(String orderNo, String json) {
		try {
			PRODUCER.send(new ProducerRecord<>(Topics.PAYMENT_COMMANDS, orderNo, json)).get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException("failed to publish test command", e);
		}
	}

	/** 없으면 null. queryForObject 는 예외를 던져 awaitility 가 즉시 실패한다. */
	protected String columnOf(String column, String orderNo) {
		return jdbcTemplate.queryForList(
				"select " + column + " from payment where order_no = ?", String.class, orderNo)
				.stream().findFirst().orElse(null);
	}

	/** 앞 테스트의 늦은 메시지가 전역 합계를 흔드므로 orderNo 범위로만 센다. */
	protected int countForOrder(String table, String orderNo) {
		String column = "outbox".equals(table) ? "aggregate_id" : "order_no";
		Integer count = jdbcTemplate.queryForObject(
				"select count(*) from " + table + " where " + column + " = ?", Integer.class, orderNo);
		return count == null ? 0 : count;
	}

	/**
	 * DLT 는 DB 에 흔적이 없어 이것만 테스트 컨슈머로 본다.
	 * KafkaConsumer 가 스레드 안전하지 않아 awaitility 대신 폴링 루프를 직접 돈다.
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

	protected static String headerOf(ConsumerRecord<String, String> record, String name) {
		Header header = record.headers().lastHeader(name);
		return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
	}
}
