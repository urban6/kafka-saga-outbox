package com.urban6.payment;

import com.urban6.payment.support.PaymentIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 컨텍스트 배선 카나리아. {@code ddl-auto: validate} 라 엔티티와 DDL 이 어긋나면 여기서 먼저 터지고,
 * {@code @ConfigurationProperties} record 의 바인딩 실패도 부팅을 막는다.
 */
class PaymentApplicationTests extends PaymentIntegrationTest {

	@Test
	@DisplayName("컨텍스트가 뜬다 — 엔티티/DDL 정합과 프로퍼티 바인딩이 함께 검증된다")
	void contextLoads() {
	}
}
