package com.ecommerce.orderservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ecommerce.orderservice.client.InventoryServiceGateway;
import com.ecommerce.orderservice.domain.OrderStatus;
import com.ecommerce.orderservice.domain.SagaStep;
import com.ecommerce.orderservice.entity.Order;
import com.ecommerce.orderservice.exception.ServiceUnavailableException;
import com.ecommerce.orderservice.repository.OrderRepository;

@ExtendWith(MockitoExtension.class)
class OrderRecoveryJobTest {

    private static final Duration STUCK_AFTER = Duration.ofMinutes(1);

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private InventoryServiceGateway inventoryGateway;

    private OrderRecoveryJob job;

    @BeforeEach
    void setUp() {
        job = new OrderRecoveryJob(orderRepository, inventoryGateway, STUCK_AFTER);
    }

    @Test
    void recover_shouldReleaseStockThenCancel_whenOrderStuckBeforePayment() {
        Order stuck = stuckOrder(41L);
        when(orderRepository.findByStatusAndSagaStepAndCreatedAtBefore(any(), any(), any()))
                .thenReturn(List.of(stuck));
        when(inventoryGateway.releaseStock(41L)).thenReturn(CompletableFuture.completedFuture(null));

        job.recoverOrdersStuckBeforePayment();

        // Release before cancel: a cancelled order with its stock still held would never be retried
        InOrder inOrder = inOrder(inventoryGateway, orderRepository);
        inOrder.verify(inventoryGateway).releaseStock(41L);
        inOrder.verify(orderRepository).save(stuck);
        assertThat(stuck.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    /** Never touches orders that may have been charged, nor orders whose request may still be running. */
    @Test
    void recover_shouldOnlySelectOrdersBeforePayment_olderThanThreshold() {
        when(orderRepository.findByStatusAndSagaStepAndCreatedAtBefore(any(), any(), any())).thenReturn(List.of());

        Instant before = Instant.now();
        job.recoverOrdersStuckBeforePayment();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(orderRepository).findByStatusAndSagaStepAndCreatedAtBefore(
                eq(OrderStatus.PENDING), eq(SagaStep.RESERVING_STOCK), cutoff.capture());
        assertThat(cutoff.getValue()).isBetween(before.minus(STUCK_AFTER), after.minus(STUCK_AFTER));
    }

    @Test
    void recover_shouldKeepOrderPending_andRecoverTheOthers_whenOneReleaseFails() {
        Order failing = stuckOrder(41L);
        Order healthy = stuckOrder(42L);
        when(orderRepository.findByStatusAndSagaStepAndCreatedAtBefore(any(), any(), any()))
                .thenReturn(List.of(failing, healthy));
        when(inventoryGateway.releaseStock(41L)).thenReturn(
                CompletableFuture.failedFuture(new ServiceUnavailableException("inventory-service", null)));
        when(inventoryGateway.releaseStock(42L)).thenReturn(CompletableFuture.completedFuture(null));

        job.recoverOrdersStuckBeforePayment();

        // Still PENDING and RESERVING_STOCK, so the next run selects it again
        assertThat(failing.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(orderRepository, never()).save(failing);
        // One failure must not stop the run
        assertThat(healthy.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(orderRepository).save(healthy);
    }

    private Order stuckOrder(Long id) {
        return Order.builder()
                .id(id)
                .status(OrderStatus.PENDING)
                .sagaStep(SagaStep.RESERVING_STOCK)
                .createdAt(Instant.now().minus(Duration.ofMinutes(5)))
                .build();
    }
}
