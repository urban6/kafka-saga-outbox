package com.urban6.order.application;

import com.urban6.order.api.dto.PlaceOrderRequest;
import com.urban6.order.api.dto.PlaceOrderResponse;
import com.urban6.order.domain.OrderStatus;
import com.urban6.order.domain.OutOfStockException;
import com.urban6.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

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
}
