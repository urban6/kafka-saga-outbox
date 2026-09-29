package com.urban6.order.domain;

public enum SagaStatus {

	STARTED,
	COMPLETED,
	CANCELED;

	public boolean isTerminated() {
		return this == COMPLETED || this == CANCELED;
	}
}
