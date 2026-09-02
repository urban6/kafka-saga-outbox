package com.urban6.payment.mockpg;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Mock PG 장애 주입 스위치.
 * <p>
 * <b>record 가 아니다.</b> 확률만으로는 "이번 호출은 반드시 타임아웃" 을 만들 수 없어 검증이
 * 재현되지 않는다. 앱 재시작 없이 켜고 끌 수 있어야 하므로 가변이고, Tomcat 워커 여러 개가
 * 동시에 읽으므로 필드는 {@code volatile} 이다.
 * <p>
 * 이 클래스가 {@code mockpg} 패키지에 있는 이유는 payment 도메인이 이걸 알면 안 되기 때문이다.
 * 장애는 <b>외부 시스템의 사정</b>이지 우리 설정이 아니다.
 */
@Component
@ConfigurationProperties(prefix = "mockpg")
public class MockPgFaults {

	/** 카드사 거절 비율. 확정된 실패다 — 재시도해도 결과가 같다. */
	private volatile double rejectRate;

	/** 500 응답 비율. 선점을 되돌리고 던지므로 재시도하면 성공한다. */
	private volatile double errorRate;

	/** 지연을 넣을 비율. {@link #delay} 가 0 이면 무의미하다. */
	private volatile double delayRate;

	/** 승인 판정 전에 붙잡아 둘 시간. {@code pg.read-timeout} 보다 크면 in-doubt 가 된다. */
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
