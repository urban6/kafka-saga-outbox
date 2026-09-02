package com.urban6.payment.infra.messaging;

import java.math.BigDecimal;

/**
 * order 가 보낸 것과 필드 이름은 같지만 <b>별개의 타입</b>이다. 공유하면 order 가 계약을 바꿀 때
 * payment 가 재빌드돼야 해서 독립 배포가 깨진다.
 * <p>
 * 자기가 쓰는 필드만 선언한다. order 가 필드를 추가해도 그대로 돈다.
 */
public record ApprovePaymentCommand(
		String orderNo,
		BigDecimal amount
) {
}
