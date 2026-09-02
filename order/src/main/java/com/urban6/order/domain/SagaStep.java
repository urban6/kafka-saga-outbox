package com.urban6.order.domain;

/**
 * 사가가 지금 회신을 기다리고 있는 <b>원격</b> 단계.
 * <p>
 * 재고 예약·확정·해제는 같은 DB 의 로컬 트랜잭션이라 단계가 아니다. 그래서 값이 둘뿐이다.
 * <p>
 * {@code EventType} 과 이름이 겹치지만 재사용하지 않는다. 그건 {@code infra/messaging} 에 있고
 * 도메인이 인프라를 의존하면 방향이 뒤집힌다. 수명도 다르다 — 와이어에 타입이 하나 늘어도
 * 사가 단계가 늘어나는 건 아니다.
 * <p>
 * 종료를 뜻하는 값을 두지 않는다. 끝난 뒤에도 마지막 단계를 남겨야 "어디서 끝났는지"가 보인다.
 * 종료 여부는 {@link SagaStatus} 가 말한다.
 */
public enum SagaStep {

	APPROVE_PAYMENT,  // 결제 승인 회신 대기
	CANCEL_PAYMENT    // 결제 취소(보상) 회신 대기
}
