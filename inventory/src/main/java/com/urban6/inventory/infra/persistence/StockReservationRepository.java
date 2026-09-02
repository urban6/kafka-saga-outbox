package com.urban6.inventory.infra.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.urban6.inventory.domain.StockReservation;

public interface StockReservationRepository extends JpaRepository<StockReservation, String> {

	List<StockReservation> findByOrderNo(String orderNo);
}
