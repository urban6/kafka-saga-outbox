package com.urban6.payment.mockpg;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 재시작 없이 켜고 끄려고 가변 클래스다. Tomcat 워커가 동시에 읽어 volatile. */
@Component
@ConfigurationProperties(prefix = "mockpg")
public class MockPgFaults {

	private volatile double rejectRate;

	private volatile double errorRate;

	private volatile double delayRate;

	/** pg.read-timeout 보다 크면 in-doubt 가 된다. */
	private volatile Duration delay = Duration.ZERO;

	public double getRejectRate() {
		return rejectRate;
	}

	public void setRejectRate(double rejectRate) {
		this.rejectRate = rejectRate;
	}

	public double getErrorRate() {
		return errorRate;
	}

	public void setErrorRate(double errorRate) {
		this.errorRate = errorRate;
	}

	public double getDelayRate() {
		return delayRate;
	}

	public void setDelayRate(double delayRate) {
		this.delayRate = delayRate;
	}

	public Duration getDelay() {
		return delay;
	}

	public void setDelay(Duration delay) {
		this.delay = delay == null ? Duration.ZERO : delay;
	}
}
