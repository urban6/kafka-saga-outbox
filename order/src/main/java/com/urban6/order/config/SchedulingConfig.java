package com.urban6.order.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 이 프로젝트의 첫 스케줄링 지점.
 * <p>
 * {@code OrderApplication} 에 애노테이션을 더 붙이지 않는다. 진입점은 "앱이 여기서 시작한다"만
 * 말해야 하고, 배치가 왜 도는지는 config 에 있어야 찾을 수 있다.
 * <p>
 * 스케줄러 스레드 풀 기본값은 <b>1개</b>다. Stuck 탐지와 정리 배치 둘이 됐으므로
 * {@code spring.task.scheduling.pool.size} 를 올렸다 — 안 올리면 정리 배치가 오래 걸릴 때
 * Stuck 탐지가 그만큼 밀리고, 알람이 늦는다.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({StuckSagaProperties.class, RetentionProperties.class})
public class SchedulingConfig {
}
