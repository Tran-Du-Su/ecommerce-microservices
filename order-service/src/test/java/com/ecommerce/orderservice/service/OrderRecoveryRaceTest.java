package com.ecommerce.orderservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ecommerce.orderservice.client.InventoryServiceGateway;
import com.ecommerce.orderservice.client.PaymentServiceGateway;
import com.ecommerce.orderservice.domain.OrderStatus;
import com.ecommerce.orderservice.domain.PaymentStatus;
import com.ecommerce.orderservice.domain.SagaStep;
import com.ecommerce.orderservice.dto.OrderItemRequest;
import com.ecommerce.orderservice.dto.OrderRequest;
import com.ecommerce.orderservice.dto.OrderResponse;
import com.ecommerce.orderservice.dto.PaymentResponse;
import com.ecommerce.orderservice.entity.Order;
import com.ecommerce.orderservice.entity.ProductView;
import com.ecommerce.orderservice.event.ProductChangedEvent;
import com.ecommerce.orderservice.repository.OrderRepository;
import com.ecommerce.orderservice.repository.ProductViewRepository;

/**
 * The recovery job and a slow request working on the same order, on a real database with real
 * commits (no test transaction), so the version checks run as in production. Whatever the timing,
 * exactly one side may act: never "charged and stock given back".
 */
@DataJpaTest
@Import(OrderService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderRecoveryRaceTest {

    private static final long PRODUCT_ID = 100L;

    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private ProductViewRepository productViewRepository;
    @MockitoBean
    private InventoryServiceGateway inventoryGateway;
    @MockitoBean
    private PaymentServiceGateway paymentGateway;

    private final CountDownLatch reserveStarted = new CountDownLatch(1);
    private final CountDownLatch inventoryAnswers = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        productViewRepository.save(ProductView.from(new ProductChangedEvent(PRODUCT_ID, "Mouse",
                new BigDecimal("500000"), 1L, false, Instant.now())));
        when(inventoryGateway.checkInventory(any())).thenReturn(CompletableFuture.completedFuture(List.of()));
        // Inventory holds its answer until the test lets it go, keeping the request mid-saga
        when(inventoryGateway.reserveStock(anyLong(), any())).thenAnswer(invocation -> CompletableFuture.supplyAsync(() -> {
            reserveStarted.countDown();
            await(inventoryAnswers);
            return null; // 204: stock reserved
        }));
        when(inventoryGateway.releaseStock(anyLong())).thenReturn(CompletableFuture.completedFuture(null));
        when(paymentGateway.pay(anyString(), any())).thenReturn(CompletableFuture.completedFuture(
                new PaymentResponse(10L, 1L, new BigDecimal("1000000"), PaymentStatus.SUCCESS, "tx-1", null)));
    }

    @AfterEach
    void cleanUp() {
        orderRepository.deleteAll();
        productViewRepository.deleteAll();
    }

    @Test
    void requestMustNotCharge_whenRecoveryClaimedTheOrderFirst() throws Exception {
        CompletableFuture<OrderResponse> request = placeOrderInBackground();
        await(reserveStarted);

        // The job runs while the request still waits on inventory
        recoveryJob(orderRepository).recoverOrdersStuckBeforePayment();
        inventoryAnswers.countDown();

        // The request's save of PAYMENT_REQUESTED loses on the version check, before any charge
        assertThatThrownBy(() -> request.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(paymentGateway, never()).pay(anyString(), any());
        verify(inventoryGateway).releaseStock(anyLong());
        assertThat(theOrder().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(theOrder().getSagaStep()).isEqualTo(SagaStep.COMPENSATING);
    }

    @Test
    void recoveryMustNotRelease_whenRequestMovedOnFirst() throws Exception {
        CompletableFuture<OrderResponse> request = placeOrderInBackground();
        await(reserveStarted);

        // The job reads the order (still RESERVING_STOCK), then the request finishes before the job claims it
        OrderRepository jobRepository = mock(OrderRepository.class, AdditionalAnswers.delegatesTo(orderRepository));
        doAnswer(invocation -> {
            List<Order> stale = orderRepository.findByStatusAndSagaStepInAndCreatedAtBefore(
                    invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            inventoryAnswers.countDown();
            request.get(5, TimeUnit.SECONDS);
            return stale;
        }).when(jobRepository).findByStatusAndSagaStepInAndCreatedAtBefore(any(), any(), any());

        recoveryJob(jobRepository).recoverOrdersStuckBeforePayment();

        // The claim loses on the version check, before any release
        assertThat(request.get().status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(inventoryGateway, never()).releaseStock(anyLong());
        assertThat(theOrder().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(theOrder().getSagaStep()).isEqualTo(SagaStep.PAYMENT_REQUESTED);
    }

    /** A negative age puts the cutoff in the future: the brand-new order counts as stuck. */
    private OrderRecoveryJob recoveryJob(OrderRepository repository) {
        return new OrderRecoveryJob(repository, inventoryGateway, Duration.ofSeconds(-1));
    }

    private CompletableFuture<OrderResponse> placeOrderInBackground() {
        return CompletableFuture.supplyAsync(() -> orderService.createOrder(new OrderRequest(1L,
                List.of(new OrderItemRequest(PRODUCT_ID, 2L, new BigDecimal("500000"))), "MOMO", "TOKEN_OK")));
    }

    private Order theOrder() {
        return orderRepository.findAll().getFirst();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
