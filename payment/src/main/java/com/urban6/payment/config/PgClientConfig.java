package com.urban6.payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * PG 호출용 {@link RestClient}.
 * <p>
 * 타임아웃을 반드시 명시한다. 기본값(무제한)이면 PG 가 응답을 안 줄 때 호출 스레드가
 * 영원히 묶인다. 나중에 Kafka 리스너가 이 경로를 타면 파티션 하나가 통째로 멈추게 된다.
 * <p>
 * Boot 4 는 {@code RestClient.Builder} 오토컨피그를 별도 모듈({@code spring-boot-restclient})로
 * 분리했다. 여기서는 baseUrl 과 요청 팩토리를 어차피 직접 지정하므로 의존성을 늘리지 않고
 * {@code RestClient.builder()} 로 만든다.
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
