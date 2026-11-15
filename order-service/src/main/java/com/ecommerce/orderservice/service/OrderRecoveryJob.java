package com.ecommerce.orderservice.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ecommerce.orderservice.client.InventoryServiceGateway;
import com.ecommerce.orderservice.domain.OrderStatus;
import com.ecommerce.orderservice.domain.SagaStep;
import com.ecommerce.orderservice.entity.Order;
import com.ecommerce.orderservice.repository.OrderRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Finishes orders whose saga stopped before the payment step (the request timed out on the
 * stock reservation, or the service died), so they do not stay {@code PENDING} with stock held.
 *
 * <p>Only {@link SagaStep#RESERVING_STOCK} is handled: no payment was ever requested, so going
 * back (release, then cancel) cannot leave a customer charged for a cancelled order. Orders at
 * {@link SagaStep#PAYMENT_REQUESTED} or with no step at all are never selected here.
 */
@Slf4j
@Component
public class OrderRecoveryJob {

    private final OrderRepository orderRepository;
    private final InventoryServiceGateway inventoryGateway;
    private final Duration stuckAfter;

    public OrderRecoveryJob(OrderRepository orderRepository, InventoryServiceGateway inventoryGateway,
            @Value("${order.recovery.stuck-after:PT1M}") Duration stuckAfter) {
        this.orderRepository = orderRepository;
        this.inventoryGateway = inventoryGateway;
        this.stuckAfter = stuckAfter;
    }

    /**
     * Runs on the scheduler thread, never on a request thread. Each order commits on its own, so
     * one failure does not undo the orders already recovered in the same run.
     */
    @Scheduled(fixedDelayString = "${order.recovery.interval:PT30S}",
            initialDelayString = "${order.recovery.interval:PT30S}")
    public void recoverOrdersStuckBeforePayment() {
        // Leaves alone orders whose request may still be running (reserve retries take a few seconds)
        Instant cutoff = Instant.now().minus(stuckAfter);
        List<Order> stuck = orderRepository.findByStatusAndSagaStepAndCreatedAtBefore(
                OrderStatus.PENDING, SagaStep.RESERVING_STOCK, cutoff);

        for (Order order : stuck) {
            try {
                // Release first: a failed release must leave the order PENDING so the next run
                // tries again, never CANCELLED with its stock still held
                inventoryGateway.releaseStock(order.getId()).join();
                order.cancel();
                orderRepository.save(order);
                log.info("Recovered order {}: stock released, order cancelled", order.getId());
            } catch (RuntimeException e) {
                log.warn("Could not recover order {}, will retry on the next run", order.getId(), e);
            }
        }
    }
}
