package com.urban6.payment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * payment 의 스케줄링 지점. order 쪽과 같은 이유로 진입점이 아니라 config 에 둔다.
 *
 * order 의 Stuck 탐지는 읽고 알리기만 하지만 이쪽은 PG 를 부르고 DB 를 고친다.
 * 인스턴스가 여러 대가 되는 순간 같은 결제를 동시에 확정하려 든다 —
 * 지금은 조건부 UPDATE(where status = IN_PROGRESS)가 뒤늦은 쪽을 0건으로 떨어뜨려 막고 있고,
 * 그래서 락이 없다. 다만 PG 조회는 중복으로 나간다.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({InDoubtProperties.class, RetentionProperties.class})
public class SchedulingConfig {
}
