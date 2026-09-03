package com.urban6.order;

import com.urban6.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 컨텍스트 배선 카나리아. 스케줄러·프로퍼티 바인딩·JPA 매핑처럼 부팅에서만 드러나는 것들을 잡는다.
 *
 * ddl-auto: validate 라 엔티티와 DDL 이 어긋나면 여기서 먼저 터진다.
 * @ConfigurationProperties record 의 바인딩 실패도 마찬가지다 —
 * 30s 를 30초 로 쓰거나 없는 SagaStep 이름을 넣으면 앱이 아예 안 뜬다.
 */
class OrderApplicationTests extends OrderIntegrationTest {

	@Test
	@DisplayName("컨텍스트가 뜬다 — 엔티티/DDL 정합과 프로퍼티 바인딩이 함께 검증된다")
	void contextLoads() {
	}
}
