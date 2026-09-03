package com.urban6.order.application;

import com.urban6.order.api.dto.PlaceOrderRequest;
import com.urban6.order.api.dto.PlaceOrderResponse;
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

/**
 * 주문 접수가 정말로 한 트랜잭션인가를 확인한다.
 *
 * "네 개를 한 트랜잭션에 담았다" 는 주장은 커밋될 때가 아니라 롤백될 때 증명된다.
 * 그래서 이 클래스의 절반은 실패 경로다 — 재고가 모자랄 때 주문·예약·사가·outbox·멱등 선점이
 * 하나도 남지 않아야 한다. 하나라도 남으면 그 주문은 영영 진행도 재시도도 못 한다.
 */
class PlaceOrderServiceIntegrationTest extends OrderIntegrationTest {

	@Autowired
	private PlaceOrderService placeOrderService;

	private static PlaceOrderRequest request(String customerId, String productId, int quantity) {
		return new PlaceOrderRequest(customerId, List.of(new PlaceOrderRequest.Item(productId, quantity)));
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

		// 재고는 아직 빠지지 않았다. 잡아뒀을 뿐이다 — 확정은 결제 승인 회신이 온 뒤다.
		assertThat(reservedOf("P-1001")).isEqualTo(2);
		assertThat(totalOf("P-1001")).isEqualTo(100);

		// outbox 에는 APPROVE_PAYMENT 하나. 주문 접수가 곧 사가의 시작이다.
		assertThat(jdbcTemplate.queryForObject(
				"select event_type from outbox where aggregate_id = ?", String.class, response.orderNo()))
				.isEqualTo("APPROVE_PAYMENT");
		assertThat(jdbcTemplate.queryForObject(
				"select topic from outbox where aggregate_id = ?", String.class, response.orderNo()))
				.isEqualTo("payment.commands");

		// 사가는 결제 승인을 기다리는 상태로 시작한다.
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

		// P-1003 은 시드 재고가 2다.
		assertThatThrownBy(() -> placeOrderService.place(key, request("C-1", "P-1003", 3)))
				.isInstanceOf(OutOfStockException.class);

		assertThat(countOf("orders")).isZero();
		assertThat(countOf("saga_instance")).isZero();
		assertThat(countOf("outbox")).isZero();
		assertThat(reservedOf("P-1003")).isZero();

		// 여기가 이 테스트의 핵심이다. 선점만 남으면 그 키는 영영 못 쓰고,
		// 클라이언트는 "이미 처리됨" 응답을 받는데 정작 주문은 없는 상태가 된다.
		assertThat(countOf("api_idempotency")).isZero();

		// 실제로 같은 키가 다시 통해야 한다.
		PlaceOrderResponse retried = placeOrderService.place(key, request("C-1", "P-1003", 2));
		assertThat(retried.status()).isEqualTo(OrderStatus.PENDING);
		assertThat(reservedOf("P-1003")).isEqualTo(2);
	}

	@Test
	@DisplayName("뒷 라인 예약이 실패하면 앞 라인 예약도 남지 않는다")
	void rollsBackEarlierLineWhenLaterLineFails() {
		// 앞 라인은 통과하고 뒷 라인에서 터진다. 재고가 서비스 밖에 있었다면
		// 앞 라인 예약이 그대로 커밋됐을 상황이다 — order 로 흡수했으므로 그냥 롤백하면 된다.
		PlaceOrderRequest mixed = new PlaceOrderRequest("C-1", List.of(
				new PlaceOrderRequest.Item("P-1001", 1),
				new PlaceOrderRequest.Item("P-1003", 99)));

		assertThatThrownBy(() -> placeOrderService.place(newKey(), mixed))
				.isInstanceOf(OutOfStockException.class);

		assertThat(reservedOf("P-1001")).isZero();
		assertThat(reservedOf("P-1003")).isZero();
		assertThat(countOf("orders")).isZero();
	}

	@Test
	@DisplayName("검증을 우회한 중복 라인은 조용히 합쳐지지 않고 터진다")
	void throwsInsteadOfMergingDuplicateLines() {
		// PlaceOrderRequest 의 제약이 막는 입력이지만, 서비스를 직접 부르면 그 층을 지나친다.
		// 가드가 없으면 여기서 조용히 5개로 합쳐지고 order_item 이 상품당 한 행이라는 전제가
		// 깨진 채 커밋된다. 그 뒤엔 확정 수량이 예약 수량과 어긋나 재고가 샌다.
		PlaceOrderRequest duplicated = new PlaceOrderRequest("C-1", List.of(
				new PlaceOrderRequest.Item("P-1001", 2),
				new PlaceOrderRequest.Item("P-1001", 3)));

		assertThatThrownBy(() -> placeOrderService.place(newKey(), duplicated))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("duplicate productId");

		// 멱등 선점은 이 예외보다 먼저 INSERT 된다. 함께 롤백돼야 같은 키로 다시 걸 수 있다.
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
		// 커맨드도 하나여야 한다. 둘이면 PG 청구가 두 번 나간다.
		assertThat(countOf("outbox")).isEqualTo(1);
		assertThat(reservedOf("P-1001")).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 키에 다른 본문이면 거절한다 — 조용히 옛 주문을 돌려주지 않는다")
	void rejectsSameKeyWithDifferentBody() {
		String key = newKey();
		placeOrderService.place(key, request("C-1", "P-1001", 1));

		// 조용히 옛 주문을 돌려주면 클라이언트의 키 재사용 버그가 영영 안 드러난다.
		assertThatThrownBy(() -> placeOrderService.place(key, request("C-1", "P-1001", 2)))
				.isInstanceOf(IdempotencyConflictException.class);

		assertThat(countOf("orders")).isEqualTo(1);
		assertThat(reservedOf("P-1001")).isEqualTo(1);
	}

	/**
	 * 상품 순서가 엇갈린 동시 주문. 재고 UPDATE 를 요청 순서대로 돌리면 두 트랜잭션이
	 * 서로 상대가 쥔 행을 기다려 InnoDB 데드락이 나고, 진 쪽이 500 을 받는다.
	 *
	 * 변이 검증: aggregateQuantities 의 TreeMap 을 LinkedHashMap 으로
	 * 되돌리면 CannotAcquireLockException 으로 깨진다.
	 */
	@Test
	@DisplayName("상품 순서가 엇갈린 동시 주문이 데드락 없이 모두 성공한다")
	void survivesCrossOrderedConcurrentReservations() throws Exception {
		int rounds = 20;
		PlaceOrderRequest forward = new PlaceOrderRequest("C-1", List.of(
				new PlaceOrderRequest.Item("P-1001", 1),
				new PlaceOrderRequest.Item("P-1002", 1)));
		PlaceOrderRequest backward = new PlaceOrderRequest("C-2", List.of(
				new PlaceOrderRequest.Item("P-1002", 1),
				new PlaceOrderRequest.Item("P-1001", 1)));

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < rounds; round++) {
				// 두 스레드를 같은 지점에서 출발시켜야 락 구간이 실제로 겹친다.
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

		// 데드락으로 한쪽이 롤백됐다면 여기서 수량이 모자란다.
		assertThat(countOf("orders")).isEqualTo(rounds * 2);
		assertThat(reservedOf("P-1001")).isEqualTo(rounds * 2);
		assertThat(reservedOf("P-1002")).isEqualTo(rounds * 2);
	}

	private Callable<Void> placing(CyclicBarrier gate, PlaceOrderRequest request) {
		return () -> {
			gate.await(15, TimeUnit.SECONDS);
			placeOrderService.place(newKey(), request);
			return null;
		};
	}

	/**
	 * idempotency_key 는 VARCHAR(128) 이다. insert ignore 였다면
	 * 앞 128자가 같은 긴 키 둘이 같은 행으로 병합돼, 두 번째 주문이 첫 주문으로 재생됐다 —
	 * 서로 다른 주문인데 하나만 실제로 처리되는 것이다.
	 *
	 * 실서비스에서는 컨트롤러의 @Size(max = 128) 이 먼저 400 으로 끊는다.
	 * 이 테스트가 보는 건 그 뒤의 마지막 방어선이다 — 조용히 병합되는 대신 터진다.
	 */
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
