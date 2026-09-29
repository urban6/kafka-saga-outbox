package com.urban6.order.application;

import com.urban6.order.api.dto.PlaceOrderResponse;
import com.urban6.order.application.PlaceOrderCommand;
import com.urban6.order.application.exception.IdempotencyConflictException;
import com.urban6.order.domain.OrderStatus;
import com.urban6.order.domain.exception.OutOfStockException;
import com.urban6.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 한 트랜잭션이라는 주장은 롤백될 때 증명된다. 그래서 절반이 실패 경로다. */
class PlaceOrderServiceIntegrationTest extends OrderIntegrationTest {

	@Autowired
	private PlaceOrderService placeOrderService;

	private static PlaceOrderCommand request(String customerId, String productId, int quantity) {
		return new PlaceOrderCommand(customerId, List.of(new PlaceOrderCommand.Item(productId, quantity)));
	}

	private static String newKey() {
		return UUID.randomUUID().toString();
	}

	@Test
	@DisplayName("주문·항목·재고 예약·사가·커맨드가 한 커밋에 함께 남는다")
	void placesOrderAndStartsSagaInOneTransaction() {
		PlaceOrderResponse response = placeOrderService.place(newKey(), request("C-1", "P-1001", 2));

		assertThat(response.orderNo()).startsWith("ORD-");
		assertThat(response.status()).isEqualTo(OrderStatus.PENDING);

		assertThat(countOf("orders")).isEqualTo(1);
		assertThat(countOf("order_item")).isEqualTo(1);
		assertThat(countOf("saga_instance")).isEqualTo(1);
		assertThat(countOf("api_idempotency")).isEqualTo(1);

		assertThat(reservedOf("P-1001")).isEqualTo(2);
		assertThat(totalOf("P-1001")).isEqualTo(100);

		assertThat(jdbcTemplate.queryForObject(
				"select event_type from outbox where aggregate_id = ?", String.class, response.orderNo()))
				.isEqualTo("APPROVE_PAYMENT");
		assertThat(jdbcTemplate.queryForObject(
				"select topic from outbox where aggregate_id = ?", String.class, response.orderNo()))
				.isEqualTo("payment.commands");

		assertThat(jdbcTemplate.queryForObject(
				"select status from saga_instance where order_no = ?", String.class, response.orderNo()))
				.isEqualTo("STARTED");
		assertThat(jdbcTemplate.queryForObject(
				"select current_step from saga_instance where order_no = ?", String.class, response.orderNo()))
				.isEqualTo("APPROVE_PAYMENT");
	}

	@Test
	@DisplayName("재고가 모자라면 멱등 선점까지 함께 롤백된다 — 같은 키로 재시도할 수 있어야 한다")
	void rollsBackEverythingIncludingIdempotencyClaimWhenOutOfStock() {
		String key = newKey();

		assertThatThrownBy(() -> placeOrderService.place(key, request("C-1", "P-1003", 3)))
				.isInstanceOf(OutOfStockException.class);

		assertThat(countOf("orders")).isZero();
		assertThat(countOf("saga_instance")).isZero();
		assertThat(countOf("outbox")).isZero();
		assertThat(reservedOf("P-1003")).isZero();

		assertThat(countOf("api_idempotency")).isZero();

		PlaceOrderResponse retried = placeOrderService.place(key, request("C-1", "P-1003", 2));
		assertThat(retried.status()).isEqualTo(OrderStatus.PENDING);
		assertThat(reservedOf("P-1003")).isEqualTo(2);
	}

	@Test
	@DisplayName("뒷 라인 예약이 실패하면 앞 라인 예약도 남지 않는다")
	void rollsBackEarlierLineWhenLaterLineFails() {
		PlaceOrderCommand mixed = new PlaceOrderCommand("C-1", List.of(
				new PlaceOrderCommand.Item("P-1001", 1),
				new PlaceOrderCommand.Item("P-1003", 99)));

		assertThatThrownBy(() -> placeOrderService.place(newKey(), mixed))
				.isInstanceOf(OutOfStockException.class);

		assertThat(reservedOf("P-1001")).isZero();
		assertThat(reservedOf("P-1003")).isZero();
		assertThat(countOf("orders")).isZero();
	}

	@Test
	@DisplayName("검증을 우회한 중복 라인은 조용히 합쳐지지 않고 터진다")
	void throwsInsteadOfMergingDuplicateLines() {
		// DTO 검증을 건너뛰고 서비스를 직접 부른다.
		PlaceOrderCommand duplicated = new PlaceOrderCommand("C-1", List.of(
				new PlaceOrderCommand.Item("P-1001", 2),
				new PlaceOrderCommand.Item("P-1001", 3)));

		assertThatThrownBy(() -> placeOrderService.place(newKey(), duplicated))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("duplicate productId");

		assertThat(countOf("orders")).isZero();
		assertThat(countOf("api_idempotency")).isZero();
		assertThat(reservedOf("P-1001")).isZero();
	}

	@Test
	@DisplayName("같은 키로 다시 오면 새 주문을 만들지 않고 같은 주문을 돌려준다")
	void replaysSameOrderForSameKey() {
		String key = newKey();
		PlaceOrderResponse first = placeOrderService.place(key, request("C-1", "P-1001", 1));
		PlaceOrderResponse second = placeOrderService.place(key, request("C-1", "P-1001", 1));

		assertThat(second.orderNo()).isEqualTo(first.orderNo());
		assertThat(countOf("orders")).isEqualTo(1);
		assertThat(countOf("outbox")).isEqualTo(1);
		assertThat(reservedOf("P-1001")).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 키에 다른 본문이면 거절한다 — 조용히 옛 주문을 돌려주지 않는다")
	void rejectsSameKeyWithDifferentBody() {
		String key = newKey();
		placeOrderService.place(key, request("C-1", "P-1001", 1));

		assertThatThrownBy(() -> placeOrderService.place(key, request("C-1", "P-1001", 2)))
				.isInstanceOf(IdempotencyConflictException.class);

		assertThat(countOf("orders")).isEqualTo(1);
		assertThat(reservedOf("P-1001")).isEqualTo(1);
	}

	/** 재고 UPDATE 순서를 상품 ID 로 정렬(TreeMap)하지 않으면 여기서 데드락이 난다. */
	@Test
	@DisplayName("상품 순서가 엇갈린 동시 주문이 데드락 없이 모두 성공한다")
	void survivesCrossOrderedConcurrentReservations() throws Exception {
		int rounds = 20;
		PlaceOrderCommand forward = new PlaceOrderCommand("C-1", List.of(
				new PlaceOrderCommand.Item("P-1001", 1),
				new PlaceOrderCommand.Item("P-1002", 1)));
		PlaceOrderCommand backward = new PlaceOrderCommand("C-2", List.of(
				new PlaceOrderCommand.Item("P-1002", 1),
				new PlaceOrderCommand.Item("P-1001", 1)));

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < rounds; round++) {
				CyclicBarrier gate = new CyclicBarrier(2);
				Future<Void> a = pool.submit(placing(gate, forward));
				Future<Void> b = pool.submit(placing(gate, backward));
				a.get(15, TimeUnit.SECONDS);
				b.get(15, TimeUnit.SECONDS);
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(countOf("orders")).isEqualTo(rounds * 2);
		assertThat(reservedOf("P-1001")).isEqualTo(rounds * 2);
		assertThat(reservedOf("P-1002")).isEqualTo(rounds * 2);
	}

	private Callable<Void> placing(CyclicBarrier gate, PlaceOrderCommand request) {
		return () -> {
			gate.await(15, TimeUnit.SECONDS);
			placeOrderService.place(newKey(), request);
			return null;
		};
	}

	/** 컨트롤러 @Size 뒤의 마지막 방어선. 잘려 병합되면 다른 주문이 앞 주문으로 재생된다. */
	@Test
	@DisplayName("128자를 넘는 멱등키는 조용히 잘려 병합되지 않고 롤백된다")
	void rejectsOverlongIdempotencyKeyInsteadOfTruncating() {
		String overlong = "K".repeat(128) + "A";

		assertThatThrownBy(() -> placeOrderService.place(overlong, request("C-1", "P-1001", 1)))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(countOf("api_idempotency")).isZero();
		assertThat(countOf("orders")).isZero();
		assertThat(reservedOf("P-1001")).isZero();
	}
}
