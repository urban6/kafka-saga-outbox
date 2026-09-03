package com.urban6.payment.infra.persistence;

import com.urban6.payment.domain.BillingKey;
import org.springframework.data.jpa.repository.JpaRepository;

/** PK 가 {@code customer_id} 라 {@code findById(customerId)} 가 곧 "이 고객의 빌링키" 다. */
public interface BillingKeyRepository extends JpaRepository<BillingKey, String> {
}
