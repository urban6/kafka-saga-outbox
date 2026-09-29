package com.urban6.order.domain;

/** 원격 단계만 둔다. 재고는 로컬 트랜잭션이라 단계가 아니다. */
public enum SagaStep {

	APPROVE_PAYMENT
}
