package com.urban6.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;


@SpringBootApplication(scanBasePackages = {
		"com.urban6.order",
		"com.urban6.outbox"
})
@EntityScan(basePackages = {
		"com.urban6.order.domain",
		"com.urban6.outbox"
})
@EnableJpaRepositories(basePackages = {
		"com.urban6.order.infra.persistence",
		"com.urban6.outbox"
})
public class OrderApplication {

	public static void main(String[] args) {
		SpringApplication.run(OrderApplication.class, args);
	}
}
