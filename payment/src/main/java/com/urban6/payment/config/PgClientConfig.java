package com.urban6.payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * PG 호출용 RestClient. 타임아웃을 반드시 명시한다 — 기본값이 무제한이라
 * Kafka 리스너가 이 경로를 탈 때 파티션 하나가 통째로 멈출 수 있다.
 *
 * Boot 4 는 RestClient.Builder 오토컨피그가 별도 모듈이라 builder() 로 직접 만든다.
 */
@Configuration
public class PgClientConfig {

	@Bean
	public RestClient pgRestClient(
			@Value("${pg.base-url}") String baseUrl,
			@Value("${pg.connect-timeout}") Duration connectTimeout,
			@Value("${pg.read-timeout}") Duration readTimeout) {

		var factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(connectTimeout);
		factory.setReadTimeout(readTimeout);

		return RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(factory)
				.build();
	}
}
