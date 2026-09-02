package com.urban6.payment.infra.persistence;

import com.urban6.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, String> {

	Optional<Payment> findByOrderNo(String orderNo);
}
