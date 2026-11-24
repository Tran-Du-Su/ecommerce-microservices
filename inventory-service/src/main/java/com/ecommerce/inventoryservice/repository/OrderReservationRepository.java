package com.ecommerce.inventoryservice.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ecommerce.inventoryservice.entity.OrderReservation;

public interface OrderReservationRepository extends JpaRepository<OrderReservation, Long> {

    boolean existsByOrderId(Long orderId);

    Optional<OrderReservation> findByOrderId(Long orderId);
}
