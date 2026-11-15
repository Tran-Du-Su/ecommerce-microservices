package com.ecommerce.orderservice.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
 * <p>Only orders before the payment step are handled ({@link SagaStep#RESERVING_STOCK}, or
 * {@link SagaStep#COMPENSATING} when an earlier run could not release): no payment was ever
 * requested, so going back cannot leave a customer charged for a cancelled order. Orders at
 * {@link SagaStep#PAYMENT_REQUESTED} or with no step at all are never selected here.
 *
 * <p>The request that created the order may still be running. The order's version makes exactly
 * one of them win: the job claims the order ({@code COMPENSATING}) before releasing anything, and
 * whichever saves second fails instead of overwriting the other.
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
        // Makes the race with a running request rare (reserve retries take a few seconds); the
        // version check below is what makes it harmless
        Instant cutoff = Instant.now().minus(stuckAfter);
        List<Order> stuck = orderRepository.findByStatusAndSagaStepInAndCreatedAtBefore(
                OrderStatus.PENDING, List.of(SagaStep.RESERVING_STOCK, SagaStep.COMPENSATING), cutoff);

        for (Order order : stuck) {
            try {
                recover(order);
                log.info("Recovered order {}: stock released, order cancelled", order.getId());
            } catch (ObjectOptimisticLockingFailureException e) {
                // The request saved first, so it has moved on (towards payment): not ours to undo
                log.info("Order {} changed while recovering it, left to its request", order.getId());
            } catch (RuntimeException e) {
                log.warn("Could not recover order {}, will retry on the next run", order.getId(), e);
            }
        }
    }

    private void recover(Order order) {
        if (order.getSagaStep() == SagaStep.RESERVING_STOCK) {
            // Claim BEFORE any side effect: reading the version and comparing would leave a gap in
            // which the request can still charge; a versioned write lets only one side through.
            order.markCompensating();
            // save returns a copy carrying the new version; the argument keeps the old one
            order = orderRepository.save(order);
        }
        // Release before cancel: a failed release leaves the order COMPENSATING for the next run,
        // never CANCELLED with its stock still held
        inventoryGateway.releaseStock(order.getId()).join();
        order.cancel();
        orderRepository.save(order);
    }
}
