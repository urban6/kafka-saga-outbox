package com.urban6.order.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄링 활성화 지점. 배치가 둘(Stuck 탐지·정리)이라
 * spring.task.scheduling.pool.size 를 올려뒀다 — 기본값 1이면 하나가 다른 하나를 민다.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({StuckSagaProperties.class, RetentionProperties.class})
public class SchedulingConfig {
}
