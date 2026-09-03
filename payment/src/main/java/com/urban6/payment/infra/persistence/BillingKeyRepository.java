package com.urban6.payment.infra.persistence;

import com.urban6.payment.domain.BillingKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingKeyRepository extends JpaRepository<BillingKey, String> {
}
