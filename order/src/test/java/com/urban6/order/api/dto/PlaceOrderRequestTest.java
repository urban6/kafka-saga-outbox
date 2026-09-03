package com.urban6.order.api.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 라인 중복 거부는 요청만 보고 판정할 수 있어 DTO 제약으로 뒀다.
 * DB 를 봐야 아는 unknown product 와는 층이 다르다.
 *
 * 첫 테스트가 가장 중요하다. Hibernate Validator 가 record 에 덧붙인 isXxx() 를
 * 프로퍼티 게터로 인식하지 않으면 제약이 조용히 무시되고, 그러면 위반이 0건으로 나온다.
 */
class PlaceOrderRequestTest {

	private static final Validator validator =
			Validation.buildDefaultValidatorFactory().getValidator();

	private static PlaceOrderRequest requestOf(PlaceOrderRequest.Item... items) {
		return new PlaceOrderRequest("C-1", List.of(items));
	}

	private static PlaceOrderRequest.Item item(String productId, int quantity) {
		return new PlaceOrderRequest.Item(productId, quantity);
	}

	@Test
	@DisplayName("같은 상품이 두 라인으로 오면 거부한다")
	void rejectsDuplicateProductId() {
		Set<ConstraintViolation<PlaceOrderRequest>> violations =
				validator.validate(requestOf(item("P-1001", 2), item("P-1001", 3)));

		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.getPropertyPath().toString()).isEqualTo("itemsDistinct");
			assertThat(violation.getMessage())
					.isEqualTo("같은 상품을 여러 항목으로 나눠 보낼 수 없습니다");
		});
	}

	@Test
	@DisplayName("서로 다른 상품이면 통과한다")
	void acceptsDistinctProductIds() {
		assertThat(validator.validate(requestOf(item("P-1001", 2), item("P-1002", 1))))
				.isEmpty();
	}

	@Test
	@DisplayName("items 가 null 이면 NotEmpty 만 걸린다")
	void doesNotStackViolationsOnNullItems() {
		Set<ConstraintViolation<PlaceOrderRequest>> violations =
				validator.validate(new PlaceOrderRequest("C-1", null));

		assertThat(violations).singleElement()
				.extracting(violation -> violation.getPropertyPath().toString())
				.isEqualTo("items");
	}

	@Test
	@DisplayName("수량이 0 이면 거부한다")
	void rejectsNonPositiveQuantity() {
		Set<ConstraintViolation<PlaceOrderRequest>> violations =
				validator.validate(requestOf(item("P-1001", 0)));

		assertThat(violations).singleElement()
				.extracting(violation -> violation.getPropertyPath().toString())
				.isEqualTo("items[0].quantity");
	}
}
