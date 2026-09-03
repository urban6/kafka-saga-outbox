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
 * 스케줄러 스레드 풀은 기본 <b>1개</b>다. 지금은 배치가 하나뿐이라 충분하지만,
 * 정리 배치가 붙으면 하나가 늦어질 때 나머지가 밀린다.
 * 그때 {@code spring.task.scheduling.pool.size} 를 올린다.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(StuckSagaProperties.class)
public class SchedulingConfig {
}
