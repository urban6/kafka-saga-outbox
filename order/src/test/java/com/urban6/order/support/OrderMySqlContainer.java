package com.urban6.order.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * 두 기반 클래스가 공유하는 MySQL. static 초기화로 한 번만 띄운다(@Testcontainers 를 안 쓰는 이유).
 * 운영 DDL 을 그대로 마운트한다 — 테스트용 스키마를 따로 두면 언젠가 운영과 어긋난다.
 */
final class OrderMySqlContainer {

	// 새 MySQLContainer 는 제네릭이 아니라 체이닝하면 반환 타입이 좁혀진다. 그래서 문장을 나눈다.
	private static final MySQLContainer INSTANCE = new MySQLContainer(DockerImageName.parse("mysql:8.0"));

	static {
		INSTANCE.withDatabaseName("order_db");
		// caching_sha2_password 라 없으면 "RSA public key is not available" 로 끊긴다.
		INSTANCE.withUrlParam("allowPublicKeyRetrieval", "true");
		INSTANCE.withUrlParam("useSSL", "false");
		INSTANCE.withCopyFileToContainer(
				MountableFile.forHostPath("../docker/mysql/init/02-order.sql"),
				"/docker-entrypoint-initdb.d/02-order.sql");
		INSTANCE.start();
	}

	private OrderMySqlContainer() {
	}

	static void registerTo(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", INSTANCE::getJdbcUrl);
		registry.add("spring.datasource.username", INSTANCE::getUsername);
		registry.add("spring.datasource.password", INSTANCE::getPassword);
		registry.add("spring.datasource.driver-class-name", INSTANCE::getDriverClassName);
	}
}
