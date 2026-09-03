package com.urban6.payment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * payment 의 스케줄링 활성화 지점. order 와 달리 이 배치는 PG 를 부르고 DB 를 고친다.
 * 다중 인스턴스에서 이중 확정은 조건부 UPDATE 가 막아 락이 없다.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({InDoubtProperties.class, RetentionProperties.class})
public class SchedulingConfig {
}
