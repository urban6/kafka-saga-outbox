package com.urban6.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * in-doubt 복구 배치 설정.
 *
 * @param scanInterval  자바에서 읽지 않는다. @Scheduled 가 직접 읽는다
 * @param grace         이만큼 지난 행만 본다. 갓 만들어진 미결은 아직 정상일 수 있다
 * @param escalateAfter 이만큼 지나도 안 풀리면 ERROR. 자동 해소를 포기하는 선이다
 * @param scanLimit     각 행마다 PG 를 한 번씩 부르므로 작게 잡는다
 */
@ConfigurationProperties(prefix = "payment.in-doubt")
public record InDoubtProperties(
		Duration scanInterval,
		Duration grace,
		Duration escalateAfter,
		int scanLimit) {
}
