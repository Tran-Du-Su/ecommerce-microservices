package com.ecommerce.orderservice.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ecommerce.orderservice.domain.OrderStatus;
import com.ecommerce.orderservice.domain.SagaStep;
import com.ecommerce.orderservice.entity.Order;

@DataJpaTest
class OrderRepositoryTest {

    private static final Instant NOW = Instant.now();
    private static final Instant CUTOFF = NOW.minus(Duration.ofMinutes(1));

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    /**
     * Adding a {@link SagaStep} must not need a schema change. An {@code ENUM} column only accepts
     * the values known when it was created, and {@code ddl-auto: update} never widens it.
     */
    @Test
    void sagaStepColumn_shouldBePlainVarchar_soNewStepsNeedNoSchemaChange() {
        String type = jdbcTemplate.queryForObject("""
                SELECT data_type FROM information_schema.columns
                 WHERE table_name = 'ORDERS' AND column_name = 'SAGA_STEP'
                """, String.class);

        assertThat(type).isEqualTo("CHARACTER VARYING");
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
