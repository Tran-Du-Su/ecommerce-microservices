package com.ecommerce.inventoryservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ecommerce.inventoryservice.entity.OrderReservation;

public interface OrderReservationRepository extends JpaRepository<OrderReservation, Long> {

    boolean existsByOrderId(Long orderId);
}
