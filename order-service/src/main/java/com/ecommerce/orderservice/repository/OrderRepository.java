package com.ecommerce.orderservice.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.ecommerce.orderservice.domain.OrderStatus;
import com.ecommerce.orderservice.domain.SagaStep;
import com.ecommerce.orderservice.entity.Order;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    @EntityGraph(attributePaths = { "items" })
    List<Order> findByUserId(Long userId);

    /**
     * Orders stuck at one of {@code sagaSteps} since before {@code createdBefore}. A {@code NULL} step
     * never matches ({@code saga_step IN (...)} is not true for NULL in SQL), so pre-saga orders are
     * not picked up.
     */
    List<Order> findByStatusAndSagaStepInAndCreatedAtBefore(OrderStatus status, Collection<SagaStep> sagaSteps,
            Instant createdBefore);
}
