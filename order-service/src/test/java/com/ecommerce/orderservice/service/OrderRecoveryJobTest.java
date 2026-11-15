package com.ecommerce.orderservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.ecommerce.orderservice.client.InventoryServiceGateway;
import com.ecommerce.orderservice.domain.OrderStatus;
import com.ecommerce.orderservice.domain.SagaStep;
import com.ecommerce.orderservice.entity.Order;
import com.ecommerce.orderservice.exception.ServiceUnavailableException;
import com.ecommerce.orderservice.repository.OrderRepository;

/**
 * Decisions of the job, with mocks. The race with a running request, which needs real version
 * checks, is covered by {@link OrderRecoveryRaceTest}.
 */
@ExtendWith(MockitoExtension.class)
class OrderRecoveryJobTest {

    private static final Duration STUCK_AFTER = Duration.ofMinutes(1);

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private InventoryServiceGateway inventoryGateway;

    private OrderRecoveryJob job;

    /** Saves and releases in call order, each save as the status/step it wrote at that moment. */
    private final List<String> timeline = new ArrayList<>();

    @BeforeEach
    void setUp() {
        job = new OrderRecoveryJob(orderRepository, inventoryGateway, STUCK_AFTER);
    }

    @Test
    void recover_shouldClaimThenReleaseThenCancel_whenOrderStuckBeforePayment() {
        stubFind(stuckOrder(41L, SagaStep.RESERVING_STOCK));
        recordSaves();
        recordRelease(41L);

        job.recoverOrdersStuckBeforePayment();

        // The claim is committed before the stock goes back: a request that saves after it fails
        // instead of charging. Cancelled only once the release succeeded.
        assertThat(timeline).containsExactly(
                "save PENDING/COMPENSATING",
                "release 41",
                "save CANCELLED/COMPENSATING");
    }

    /** An earlier run claimed the order but could not release: resume, do not claim again. */
    @Test
    void recover_shouldResumeWithoutClaimingAgain_whenAlreadyCompensating() {
        stubFind(stuckOrder(41L, SagaStep.COMPENSATING));
        recordSaves();
        recordRelease(41L);

        job.recoverOrdersStuckBeforePayment();

        assertThat(timeline).containsExactly("release 41", "save CANCELLED/COMPENSATING");
    }

    @Test
    void recover_shouldNotRelease_whenRequestSavedFirst() {
        stubFind(stuckOrder(41L, SagaStep.RESERVING_STOCK));
        when(orderRepository.save(any(Order.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Order.class, 41L));

        job.recoverOrdersStuckBeforePayment();

        // The request moved on towards payment: giving its stock back would oversell
        verify(inventoryGateway, never()).releaseStock(anyLong());
    }

    /** Never touches orders that may have been charged, nor orders whose request may still be running. */
    @Test
    @SuppressWarnings("unchecked")
    void recover_shouldOnlySelectOrdersBeforePayment_olderThanThreshold() {
        when(orderRepository.findByStatusAndSagaStepInAndCreatedAtBefore(any(), any(), any())).thenReturn(List.of());

        Instant before = Instant.now();
        job.recoverOrdersStuckBeforePayment();
        Instant after = Instant.now();

        ArgumentCaptor<Collection<SagaStep>> steps = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(orderRepository).findByStatusAndSagaStepInAndCreatedAtBefore(
                eq(OrderStatus.PENDING), steps.capture(), cutoff.capture());
        assertThat(steps.getValue()).containsExactlyInAnyOrder(SagaStep.RESERVING_STOCK, SagaStep.COMPENSATING);
        assertThat(cutoff.getValue()).isBetween(before.minus(STUCK_AFTER), after.minus(STUCK_AFTER));
    }

    @Test
    void recover_shouldKeepOrderPending_andRecoverTheOthers_whenOneReleaseFails() {
        Order failing = stuckOrder(41L, SagaStep.RESERVING_STOCK);
        Order healthy = stuckOrder(42L, SagaStep.RESERVING_STOCK);
        stubFind(failing, healthy);
        recordSaves();
        when(inventoryGateway.releaseStock(41L)).thenReturn(
                CompletableFuture.failedFuture(new ServiceUnavailableException("inventory-service", null)));
        when(inventoryGateway.releaseStock(42L)).thenReturn(CompletableFuture.completedFuture(null));

        job.recoverOrdersStuckBeforePayment();

        // Claimed but not cancelled: PENDING/COMPENSATING, so the next run resumes it
        assertThat(failing.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(failing.getSagaStep()).isEqualTo(SagaStep.COMPENSATING);
        // One failure must not stop the run
        assertThat(healthy.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    private void stubFind(Order... orders) {
        when(orderRepository.findByStatusAndSagaStepInAndCreatedAtBefore(any(), any(), any()))
                .thenReturn(List.of(orders));
    }

    private void recordSaves() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            timeline.add("save " + order.getStatus() + "/" + order.getSagaStep());
            return order;
        });
    }

    private void recordRelease(Long orderId) {
        when(inventoryGateway.releaseStock(orderId)).thenAnswer(invocation -> {
            timeline.add("release " + orderId);
            return CompletableFuture.completedFuture(null);
        });
    }

    private Order stuckOrder(Long id, SagaStep step) {
        return Order.builder()
                .id(id)
                .status(OrderStatus.PENDING)
                .sagaStep(step)
                .createdAt(Instant.now().minus(Duration.ofMinutes(5)))
                .build();
    }
}
