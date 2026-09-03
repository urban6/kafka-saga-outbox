package com.urban6.payment.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * payment 통합 테스트가 공유하는 MySQL 컨테이너.
 *
 * 기반 클래스마다 컨테이너를 들면 스프링 컨텍스트가 갈릴 때 MySQL 도 함께 늘어난다.
 * 여기 한 곳에 두면 기동 비용을 한 번만 낸다.
 *
 * DDL 을 복사하지 않는다. docker/mysql/init/03-payment.sql 을 그대로 마운트한다.
 * 테스트용 스키마를 따로 두면 언젠가 운영 DDL 과 어긋나고, 그때 통합 테스트는
 * 존재하지 않는 스키마를 검증하게 된다.
 */
final class PaymentMySqlContainer {

	// org.testcontainers.containers.MySQLContainer 는 2.x 에서 deprecated 다(패키지 이동).
	// 체이닝하지 않고 문장을 나눈 건 새 클래스가 제네릭이 아니라 반환 타입이 상위로 좁혀지기 때문이다.
	private static final MySQLContainer INSTANCE = new MySQLContainer(DockerImageName.parse("mysql:8.0"));

	static {
		INSTANCE.withDatabaseName("payment_db");
		// MySQL 8 기본 인증이 caching_sha2_password 라 이게 없으면
		// "RSA public key is not available client side" 로 연결이 끊긴다.
		INSTANCE.withUrlParam("allowPublicKeyRetrieval", "true");
		INSTANCE.withUrlParam("useSSL", "false");
		INSTANCE.withCopyFileToContainer(
				MountableFile.forHostPath("../docker/mysql/init/03-payment.sql"),
				"/docker-entrypoint-initdb.d/03-payment.sql");
		INSTANCE.start();
	}

	private PaymentMySqlContainer() {
	}

	static void registerTo(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", INSTANCE::getJdbcUrl);
		registry.add("spring.datasource.username", INSTANCE::getUsername);
		registry.add("spring.datasource.password", INSTANCE::getPassword);
		registry.add("spring.datasource.driver-class-name", INSTANCE::getDriverClassName);
	}
}
