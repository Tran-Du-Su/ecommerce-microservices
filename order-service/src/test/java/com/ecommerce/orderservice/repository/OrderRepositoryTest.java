package com.ecommerce.orderservice.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.ecommerce.orderservice.domain.OrderStatus;
import com.ecommerce.orderservice.domain.SagaStep;
import com.ecommerce.orderservice.entity.Order;

@DataJpaTest
class OrderRepositoryTest {

    private static final Instant NOW = Instant.now();
    private static final Instant CUTOFF = NOW.minus(Duration.ofMinutes(1));

    @Autowired
    private OrderRepository orderRepository;

    /**
     * The recovery query must pick only orders that are safe to compensate. Anything else it
     * returns would be released and cancelled by the job.
     */
    @Test
    void findStuck_shouldReturnOnlyOldPendingOrders_atTheGivenStep() {
        Order stuck = save(OrderStatus.PENDING, SagaStep.RESERVING_STOCK, NOW.minus(Duration.ofMinutes(5)));
        // claimed by an earlier run whose release failed
        Order halfRecovered = save(OrderStatus.PENDING, SagaStep.COMPENSATING, NOW.minus(Duration.ofMinutes(5)));
        save(OrderStatus.PENDING, SagaStep.RESERVING_STOCK, NOW); // its request may still be running
        save(OrderStatus.PENDING, SagaStep.PAYMENT_REQUESTED, NOW.minus(Duration.ofMinutes(5))); // may be paid
        save(OrderStatus.PENDING, null, NOW.minus(Duration.ofMinutes(5))); // created before saga steps existed
        save(OrderStatus.CANCELLED, SagaStep.RESERVING_STOCK, NOW.minus(Duration.ofMinutes(5))); // already done

        List<Order> found = orderRepository.findByStatusAndSagaStepInAndCreatedAtBefore(
                OrderStatus.PENDING, List.of(SagaStep.RESERVING_STOCK, SagaStep.COMPENSATING), CUTOFF);

        assertThat(found).extracting(Order::getId).containsExactlyInAnyOrder(stuck.getId(), halfRecovered.getId());
    }

    private Order save(OrderStatus status, SagaStep step, Instant createdAt) {
        return orderRepository.save(Order.builder()
                .userId(1L)
                .totalAmount(new BigDecimal("100000"))
                .status(status)
                .sagaStep(step)
                .createdAt(createdAt)
                .build());
    }
}
