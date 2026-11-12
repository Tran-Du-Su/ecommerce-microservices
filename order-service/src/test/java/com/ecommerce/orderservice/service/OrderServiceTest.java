package com.ecommerce.orderservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ecommerce.orderservice.client.InventoryServiceGateway;
import com.ecommerce.orderservice.client.PaymentServiceGateway;
import com.ecommerce.orderservice.domain.OrderStatus;
import com.ecommerce.orderservice.domain.PaymentStatus;
import com.ecommerce.orderservice.dto.OrderItemRequest;
import com.ecommerce.orderservice.dto.OrderRequest;
import com.ecommerce.orderservice.dto.OrderResponse;
import com.ecommerce.orderservice.dto.PaymentRequest;
import com.ecommerce.orderservice.dto.PaymentResponse;
import com.ecommerce.orderservice.dto.StockShortage;
import com.ecommerce.orderservice.entity.Order;
import com.ecommerce.orderservice.entity.ProductView;
import com.ecommerce.orderservice.event.ProductChangedEvent;
import com.ecommerce.orderservice.exception.OutOfStockException;
import com.ecommerce.orderservice.exception.PriceChangedException;
import com.ecommerce.orderservice.exception.ProductNotFoundException;
import com.ecommerce.orderservice.exception.ServiceUnavailableException;
import com.ecommerce.orderservice.repository.OrderRepository;
import com.ecommerce.orderservice.repository.ProductViewRepository;

import feign.FeignException;
import feign.Request;
import feign.Response;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Long ORDER_ID = 501L; // distinct from the user id (1), so mixing them up fails a test
    private static final Long PRODUCT_ID = 100L;

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductViewRepository productViewRepository;
    @Mock
    private InventoryServiceGateway inventoryGateway;
    @Mock
    private PaymentServiceGateway paymentGateway;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, productViewRepository, inventoryGateway, paymentGateway);
    }

    /** Tests that reach the payment step: products found, stock available, stock reserved. */
    @Nested
    class WhenStockIsReserved {

        @BeforeEach
        void stubStepsBeforePayment() {
            when(productViewRepository.findAllById(List.of(PRODUCT_ID))).thenReturn(List.of(mouse()));
            when(inventoryGateway.checkInventory(any())).thenReturn(CompletableFuture.completedFuture(List.of()));
            when(inventoryGateway.reserveStock(anyLong(), any())).thenReturn(CompletableFuture.completedFuture(null));
            when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
                Order order = invocation.getArgument(0);
                if (order.getId() == null) {
                    order.setId(ORDER_ID); // simulate the id generated on the first insert
                }
                return order;
            });
        }

        @Test
        void createOrder_shouldConfirm_andChargeAfterReservingStock_whenPaymentSucceeds() {
            when(paymentGateway.pay(anyString(), any())).thenReturn(paid(PaymentStatus.SUCCESS));

            OrderResponse response = orderService.createOrder(request());

            assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(response.totalAmount()).isEqualByComparingTo("1000000");

            // Charging is the hardest step to undo, so it must come after the stock reservation
            InOrder order = inOrder(inventoryGateway, paymentGateway);
            order.verify(inventoryGateway).reserveStock(eq(ORDER_ID), any());
            order.verify(paymentGateway).pay(anyString(), any());

            ArgumentCaptor<PaymentRequest> payment = ArgumentCaptor.forClass(PaymentRequest.class);
            verify(paymentGateway).pay(eq("order-" + ORDER_ID), payment.capture());
            assertThat(payment.getValue().orderId()).isEqualTo(ORDER_ID);
            assertThat(payment.getValue().amount()).isEqualByComparingTo("1000000");
            assertThat(payment.getValue().currency()).isEqualTo("VND");
            assertThat(payment.getValue().paymentMethod()).isEqualTo("MOMO");
            assertThat(payment.getValue().paymentToken()).isEqualTo("TOKEN_OK");

            // 1: insert PENDING, 2: persist the outcome
            verify(orderRepository, times(2)).save(any());
            verify(inventoryGateway, never()).releaseStock(anyLong());
        }

        @Test
        void createOrder_shouldReleaseStockThenCancel_whenPaymentDeclined() {
            when(paymentGateway.pay(anyString(), any())).thenReturn(paid(PaymentStatus.FAILED));
            when(inventoryGateway.releaseStock(anyLong())).thenReturn(CompletableFuture.completedFuture(null));

            OrderResponse response = orderService.createOrder(request());

            assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
            verify(orderRepository, times(2)).save(any());

            // Released by order id: inventory returns what it reserved for this order
            verify(inventoryGateway).releaseStock(ORDER_ID);
        }

        @Test
        void createOrder_shouldStayPending_andKeepStock_whenPaymentPending() {
            when(paymentGateway.pay(anyString(), any())).thenReturn(paid(PaymentStatus.PENDING));

            OrderResponse response = orderService.createOrder(request());

            assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
            verify(inventoryGateway, never()).releaseStock(anyLong());
        }

        @Test
        void createOrder_shouldStayPending_whenStockReleaseFails() {
            when(paymentGateway.pay(anyString(), any())).thenReturn(paid(PaymentStatus.FAILED));
            when(inventoryGateway.releaseStock(anyLong())).thenReturn(
                    CompletableFuture.failedFuture(new ServiceUnavailableException("inventory-service", null)));

            assertThatThrownBy(() -> orderService.createOrder(request()))
                    .isInstanceOf(ServiceUnavailableException.class);

            // Only the initial insert: never cancelled while the stock is still held
            verify(orderRepository, times(1)).save(any());
        }

        @Test
        void createOrder_shouldStayPending_withoutThrowing_whenPaymentServiceUnavailable() {
            when(paymentGateway.pay(anyString(), any())).thenReturn(
                    CompletableFuture.failedFuture(new ServiceUnavailableException("payment-service", null)));

            OrderResponse response = orderService.createOrder(request());

            assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
            // Only the initial insert: the order must not be cancelled while the charge is unknown
            verify(orderRepository, times(1)).save(any());
            // The customer may have paid, so the stock must stay taken
            verify(inventoryGateway, never()).releaseStock(anyLong());
        }

        @Test
        void createOrder_shouldReleaseStockCancelThenRethrow_whenPaymentServiceRejectsRequest() {
            FeignException rejection = feignError(400);
            when(paymentGateway.pay(anyString(), any())).thenReturn(CompletableFuture.failedFuture(rejection));
            when(inventoryGateway.releaseStock(anyLong())).thenReturn(CompletableFuture.completedFuture(null));

            assertThatThrownBy(() -> orderService.createOrder(request())).isSameAs(rejection);

            ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
            verify(orderRepository, times(2)).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(OrderStatus.CANCELLED);
            verify(inventoryGateway).releaseStock(ORDER_ID);
        }
    }

    @Test
    void createOrder_shouldNotSaveOrCharge_whenProductNotFound() {
        when(productViewRepository.findAllById(List.of(PRODUCT_ID))).thenReturn(List.of());

        assertThatThrownBy(() -> orderService.createOrder(request())).isInstanceOf(ProductNotFoundException.class);

        verify(orderRepository, never()).save(any());
        verify(paymentGateway, never()).pay(anyString(), any());
    }

    @Test
    void createOrder_shouldNotSaveOrCharge_whenProductDeleted() {
        // The view keeps deleted products (soft delete), so findAllById still returns the row
        ProductView deleted = mouse();
        deleted.apply(new ProductChangedEvent(PRODUCT_ID, null, null, 2L, true, Instant.now()));
        when(productViewRepository.findAllById(List.of(PRODUCT_ID))).thenReturn(List.of(deleted));

        assertThatThrownBy(() -> orderService.createOrder(request())).isInstanceOf(ProductNotFoundException.class);

        verify(orderRepository, never()).save(any());
        verify(paymentGateway, never()).pay(anyString(), any());
    }

    @Test
    void createOrder_shouldRejectBeforeAnyRemoteCall_whenPriceChanged() {
        when(productViewRepository.findAllById(List.of(PRODUCT_ID))).thenReturn(List.of(mouse()));

        assertThatThrownBy(() -> orderService.createOrder(request(BigDecimal.valueOf(800_000))))
                .isInstanceOfSatisfying(PriceChangedException.class,
                        e -> assertThat(e.getProductIds()).containsExactly(PRODUCT_ID));

        verify(inventoryGateway, never()).checkInventory(any());
        verify(orderRepository, never()).save(any());
        verify(paymentGateway, never()).pay(anyString(), any());
    }

    @Test
    void createOrder_shouldNotSaveOrCharge_whenOutOfStock() {
        when(productViewRepository.findAllById(List.of(PRODUCT_ID))).thenReturn(List.of(mouse()));
        when(inventoryGateway.checkInventory(any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(new StockShortage(PRODUCT_ID, 2L, 1L))));

        assertThatThrownBy(() -> orderService.createOrder(request())).isInstanceOf(OutOfStockException.class);

        verify(orderRepository, never()).save(any());
        verify(inventoryGateway, never()).reserveStock(anyLong(), any());
        verify(paymentGateway, never()).pay(anyString(), any());
    }

    @Test
    void createOrder_shouldNotCharge_whenStockReservationFails() {
        when(productViewRepository.findAllById(List.of(PRODUCT_ID))).thenReturn(List.of(mouse()));
        when(inventoryGateway.checkInventory(any())).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(ORDER_ID); // the reservation is keyed by this generated id
            return order;
        });
        when(inventoryGateway.reserveStock(anyLong(), any())).thenReturn(
                CompletableFuture.failedFuture(new ServiceUnavailableException("inventory-service", null)));

        assertThatThrownBy(() -> orderService.createOrder(request()))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(inventoryGateway).reserveStock(eq(ORDER_ID), any());
        // The customer must never be charged for stock that could not be reserved
        verify(paymentGateway, never()).pay(anyString(), any());
    }

    /** Client sends the price it showed, typically without decimals. */
    private OrderRequest request() {
        return request(BigDecimal.valueOf(500_000));
    }

    private OrderRequest request(BigDecimal expectedPrice) {
        return new OrderRequest(1L, List.of(new OrderItemRequest(PRODUCT_ID, 2L, expectedPrice)), "MOMO",
                "TOKEN_OK");
    }

    /** Same price as {@link #request()}, but with scale 2: the form the view has after a database round-trip. */
    private ProductView mouse() {
        return ProductView.from(new ProductChangedEvent(PRODUCT_ID, "Mouse", new BigDecimal("500000.00"), 1L, false,
                Instant.now()));
    }

    private CompletableFuture<PaymentResponse> paid(PaymentStatus status) {
        return CompletableFuture.completedFuture(
                new PaymentResponse(10L, ORDER_ID, new BigDecimal("1000000.00"), status, "tx-1", null));
    }

    /** Builds the same exception subclass Feign raises for the given HTTP status. */
    private FeignException feignError(int status) {
        Request feignRequest = Request.create(Request.HttpMethod.POST, "/api/payments", Map.of(), null,
                StandardCharsets.UTF_8, null);
        Response feignResponse = Response.builder()
                .status(status)
                .reason("status " + status)
                .request(feignRequest)
                .headers(Map.of())
                .body("{}", StandardCharsets.UTF_8)
                .build();
        return FeignException.errorStatus("PaymentClient#pay", feignResponse);
    }
}
