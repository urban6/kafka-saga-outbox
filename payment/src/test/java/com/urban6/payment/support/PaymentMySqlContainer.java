package com.urban6.payment.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * 두 컨텍스트가 공유하도록 static 으로 한 번만 띄운다.
 * DDL 은 복사하지 않고 운영 파일을 마운트한다 — 따로 두면 언젠가 어긋난다.
 */
final class PaymentMySqlContainer {

	// 새 MySQLContainer 는 제네릭이 아니라 체이닝하면 반환 타입이 좁혀진다.
	private static final MySQLContainer INSTANCE = new MySQLContainer(DockerImageName.parse("mysql:8.0"));

	static {
		INSTANCE.withDatabaseName("payment_db");
		// MySQL 8 caching_sha2_password 대응.
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
