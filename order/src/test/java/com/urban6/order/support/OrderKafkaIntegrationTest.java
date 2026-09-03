package com.urban6.order.support;

import com.urban6.order.infra.messaging.Topics;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 리스너를 <b>실제로 띄우고</b> 진짜 브로커로 메시지를 밀어넣는 기반.
 * <p>
 * {@link OrderIntegrationTest} 는 리스너를 끄고 유스케이스를 직접 부른다. 여기서 보는 건 그 앞단이다 —
 * 봉투 역직렬화, 모르는 타입, poison pill. 전부 <b>메시지가 브로커를 거쳐야</b> 재현되는 것들이라
 * 유스케이스를 직접 부르는 방식으로는 검증되지 않는다.
 * <p>
 * MySQL 은 {@link OrderMySqlContainer} 를 공유하고 Kafka 만 따로 띄운다. 프로퍼티가 달라
 * 스프링 컨텍스트는 갈리지만 DB 컨테이너는 하나다.
 * <p>
 * <b>토픽을 직접 만든다.</b> 자동 생성에 맡기면 파티션이 1개가 되는데, 그러면
 * "poison pill 이 <i>같은 파티션의</i> 다음 메시지를 막지 않는다" 를 증명할 수 없다 —
 * 파티션이 하나뿐이면 애초에 비교 대상이 없다. 운영과 같은 3개로 만들고 <b>키를 같게</b> 보낸다.
 */
@Tag("integration")
@SpringBootTest(properties = {
		// 이 기반의 존재 이유. 리스너가 떠 있어야 한다.
		"spring.kafka.listener.auto-startup=true",
		"saga.stuck.scan-interval=1h",
		"retention.scan-interval=1h",
})
public abstract class OrderKafkaIntegrationTest {

	private static final KafkaContainer KAFKA =
			new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));

	private static final KafkaProducer<String, String> PRODUCER;

	static {
		KAFKA.start();
		try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
			admin.createTopics(List.of(
					new NewTopic(Topics.ORDER_SAGA_REPLIES, 3, (short) 1),
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

	/**
	 * 원시 문자열을 그대로 보낸다. <b>깨진 JSON 을 보낼 수 있어야</b> poison pill 을 재현하므로
	 * {@code KafkaTemplate} 이나 봉투 객체를 쓰지 않는다.
	 * <p>
	 * 키는 {@code orderNo} 다 — 운영과 같고, 같은 키가 같은 파티션으로 가야
	 * "앞 메시지가 뒤 메시지를 막지 않는다" 가 성립한다.
	 */
	protected void publishRaw(String topic, String key, String value) {
		try {
			PRODUCER.send(new ProducerRecord<>(topic, key, value)).get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException("failed to publish test message", e);
		}
	}

	/**
	 * 행이 아직 없으면 {@code null} 을 준다. <b>{@code queryForObject} 를 쓰지 않는 이유</b>가 이거다 —
	 * 비동기라 폴링 중에는 행이 없는 게 정상인데, 예외를 던지면 awaitility 가 그 자리에서 실패한다.
	 */
	protected String statusOfOrder(String orderNo) {
		return jdbcTemplate.queryForList("select status from orders where order_no = ?", String.class, orderNo)
				.stream().findFirst().orElse(null);
	}

	protected int totalOf(String productId) {
		Integer total = jdbcTemplate.queryForObject(
				"select total_quantity from product where product_id = ?", Integer.class, productId);
		return total == null ? 0 : total;
	}

	/**
	 * <b>전역 카운트를 쓰지 않는다.</b> 리스너가 비동기라 앞 테스트의 미처리 메시지가
	 * {@code @BeforeEach} 정리 직후 도착할 수 있고, 그러면 전역 합계가 흔들린다.
	 * 주문번호는 테스트마다 새로 만들므로 이 범위 안에서는 결정적이다.
	 */
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
}
