package com.urban6.order.support;

import com.urban6.order.infra.messaging.Topics;
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
 * 리스너를 띄우고 진짜 브로커로 메시지를 미는 기반.
 * 토픽은 직접 3파티션으로 만든다 — 자동 생성(1개)이면 poison pill 이 같은 파티션 뒷 메시지를 막는지 볼 수 없다.
 */
@Tag("integration")
@SpringBootTest(properties = {
		"spring.kafka.listener.auto-startup=true",
		"saga.stuck.scan-interval=1h",
		"retention.scan-interval=1h",
})
public abstract class OrderKafkaIntegrationTest {

	private static final KafkaContainer KAFKA =
			new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));

	private static final KafkaProducer<String, String> PRODUCER;

	private static final Duration DLT_TIMEOUT = Duration.ofSeconds(30);

	static {
		KAFKA.start();
		try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
			admin.createTopics(List.of(
					new NewTopic(Topics.ORDER_SAGA_REPLIES, 3, (short) 1),
					new NewTopic(Topics.ORDER_SAGA_REPLIES_DLT, 3, (short) 1),
					new NewTopic(Topics.PAYMENT_COMMANDS, 3, (short) 1),
					new NewTopic(Topics.ORDER_EVENTS, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException("failed to create test topics", e);
		}
		PRODUCER = new KafkaProducer<>(Map.of(
				ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
				ProducerConfig.ACKS_CONFIG, "all"), new StringSerializer(), new StringSerializer());
	}

	@DynamicPropertySource
	static void containers(DynamicPropertyRegistry registry) {
		OrderMySqlContainer.registerTo(registry);
		registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
	}

	@AfterAll
	static void closeProducer() {
		PRODUCER.flush();
	}

	@Autowired
	protected JdbcTemplate jdbcTemplate;

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

	/** 깨진 JSON 도 보내야 해서 원시 문자열로 발행한다. 키가 같아야 같은 파티션에 실린다. */
	protected void publishRaw(String topic, String key, String value) {
		try {
			PRODUCER.send(new ProducerRecord<>(topic, key, value)).get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException("failed to publish test message", e);
		}
	}

	/** queryForObject 는 행이 없으면 던지고, awaitility 는 AssertionError 만 삼킨다. 그래서 null. */
	protected String statusOfOrder(String orderNo) {
		return jdbcTemplate.queryForList("select status from orders where order_no = ?", String.class, orderNo)
				.stream().findFirst().orElse(null);
	}

	protected int totalOf(String productId) {
		Integer total = jdbcTemplate.queryForObject(
				"select total_quantity from product where product_id = ?", Integer.class, productId);
		return total == null ? 0 : total;
	}

	/** 전역 카운트 금지 — 앞 테스트의 늦은 메시지가 정리 직후 도착한다. 주문번호 범위로 좁힌다. */
	protected int countForOrder(String table, String orderNo) {
		String column = "outbox".equals(table) ? "aggregate_id" : "order_no";
		Integer count = jdbcTemplate.queryForObject(
				"select count(*) from " + table + " where " + column + " = ?", Integer.class, orderNo);
		return count == null ? 0 : count;
	}

	protected int reservedOf(String productId) {
		Integer reserved = jdbcTemplate.queryForObject(
				"select reserved_quantity from product where product_id = ?", Integer.class, productId);
		return reserved == null ? 0 : reserved;
	}

	protected int countOf(String table) {
		Integer count = jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
		return count == null ? 0 : count;
	}

	/** KafkaConsumer 는 스레드 안전하지 않아 awaitility(별도 스레드) 대신 폴링 루프를 직접 돈다. */
	protected ConsumerRecord<String, String> awaitDltRecord(String orderNo) {
		Map<String, Object> props = Map.of(
				ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
				ConsumerConfig.GROUP_ID_CONFIG, "dlt-probe-" + UUID.randomUUID(),
				ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
				ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

		try (KafkaConsumer<String, String> consumer =
				 new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {

			consumer.subscribe(List.of(Topics.ORDER_SAGA_REPLIES_DLT));

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
