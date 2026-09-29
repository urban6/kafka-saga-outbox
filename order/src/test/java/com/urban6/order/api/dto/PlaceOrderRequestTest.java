package com.urban6.order.api.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** record 의 isXxx() 가 게터로 인식되지 않으면 @AssertTrue 가 조용히 무시된다. 첫 테스트가 그걸 본다. */
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
