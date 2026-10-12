package com.ecommerce.orderservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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
import com.ecommerce.orderservice.client.ProductServiceGateway;
import com.ecommerce.orderservice.domain.OrderStatus;
import com.ecommerce.orderservice.domain.PaymentStatus;
import com.ecommerce.orderservice.dto.OrderItemRequest;
import com.ecommerce.orderservice.dto.OrderRequest;
import com.ecommerce.orderservice.dto.OrderResponse;
import com.ecommerce.orderservice.dto.PaymentRequest;
import com.ecommerce.orderservice.dto.PaymentResponse;
import com.ecommerce.orderservice.dto.ProductResponse;
import com.ecommerce.orderservice.dto.StockShortage;
import com.ecommerce.orderservice.entity.Order;
import com.ecommerce.orderservice.exception.OutOfStockException;
import com.ecommerce.orderservice.exception.ProductNotFoundException;
import com.ecommerce.orderservice.exception.ServiceUnavailableException;
import com.ecommerce.orderservice.repository.OrderRepository;

import feign.FeignException;
import feign.Request;
import feign.Response;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Long ORDER_ID = 1L;
    private static final Long PRODUCT_ID = 100L;

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductServiceGateway productGateway;
    @Mock
    private InventoryServiceGateway inventoryGateway;
    @Mock
    private PaymentServiceGateway paymentGateway;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, productGateway, inventoryGateway, paymentGateway);
    }

    /** Tests that reach the payment step: products found, stock available, stock decreased. */
    @Nested
    class WhenStockIsReserved {

        @BeforeEach
        void stubStepsBeforePayment() {
            when(productGateway.getProductsByProductIds(List.of(PRODUCT_ID)))
                    .thenReturn(CompletableFuture.completedFuture(List.of(mouse())));
            when(inventoryGateway.checkInventory(any())).thenReturn(CompletableFuture.completedFuture(List.of()));
            when(inventoryGateway.decreaseInventory(any())).thenReturn(CompletableFuture.completedFuture(null));
            when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
                Order order = invocation.getArgument(0);
                if (order.getId() == null) {
                    order.setId(ORDER_ID); // simulate the id generated on the first insert
                }
                return order;
            });
        }

        @Test
        void createOrder_shouldConfirm_andChargeAfterDecreasingStock_whenPaymentSucceeds() {
            when(paymentGateway.pay(anyString(), any())).thenReturn(paid(PaymentStatus.SUCCESS));

            OrderResponse response = orderService.createOrder(request());

            assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(response.totalAmount()).isEqualByComparingTo("1000000");

            // Charging is the hardest step to undo, so it must come after the stock decrease
            InOrder order = inOrder(inventoryGateway, paymentGateway);
            order.verify(inventoryGateway).decreaseInventory(any());
            order.verify(paymentGateway).pay(anyString(), any());

            ArgumentCaptor<PaymentRequest> payment = ArgumentCaptor.forClass(PaymentRequest.class);
            verify(paymentGateway).pay(eq("order-1"), payment.capture());
            assertThat(payment.getValue().orderId()).isEqualTo(ORDER_ID);
            assertThat(payment.getValue().amount()).isEqualByComparingTo("1000000");
            assertThat(payment.getValue().currency()).isEqualTo("VND");
            assertThat(payment.getValue().paymentMethod()).isEqualTo("MOMO");
            assertThat(payment.getValue().paymentToken()).isEqualTo("TOKEN_OK");

            // 1: insert PENDING, 2: persist the outcome
            verify(orderRepository, times(2)).save(any());
        }

        @Test
        void createOrder_shouldCancel_whenPaymentDeclined() {
            when(paymentGateway.pay(anyString(), any())).thenReturn(paid(PaymentStatus.FAILED));

            OrderResponse response = orderService.createOrder(request());

            assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
            verify(orderRepository, times(2)).save(any());
        }

        @Test
        void createOrder_shouldStayPending_whenPaymentPending() {
            when(paymentGateway.pay(anyString(), any())).thenReturn(paid(PaymentStatus.PENDING));

            OrderResponse response = orderService.createOrder(request());

            assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        }

        @Test
        void createOrder_shouldStayPending_withoutThrowing_whenPaymentServiceUnavailable() {
            when(paymentGateway.pay(anyString(), any())).thenReturn(
                    CompletableFuture.failedFuture(new ServiceUnavailableException("payment-service", null)));

            OrderResponse response = orderService.createOrder(request());

            assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
            // Only the initial insert: the order must not be cancelled while the charge is unknown
            verify(orderRepository, times(1)).save(any());
        }

        @Test
        void createOrder_shouldCancelThenRethrow_whenPaymentServiceRejectsRequest() {
            FeignException rejection = feignError(400);
            when(paymentGateway.pay(anyString(), any())).thenReturn(CompletableFuture.failedFuture(rejection));

            assertThatThrownBy(() -> orderService.createOrder(request())).isSameAs(rejection);

            ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
            verify(orderRepository, times(2)).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }
    }

    @Test
    void createOrder_shouldNotSaveOrCharge_whenProductNotFound() {
        when(productGateway.getProductsByProductIds(List.of(PRODUCT_ID)))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        assertThatThrownBy(() -> orderService.createOrder(request())).isInstanceOf(ProductNotFoundException.class);

        verify(orderRepository, never()).save(any());
        verify(paymentGateway, never()).pay(anyString(), any());
    }

    @Test
    void createOrder_shouldNotSaveOrCharge_whenOutOfStock() {
        when(productGateway.getProductsByProductIds(List.of(PRODUCT_ID)))
                .thenReturn(CompletableFuture.completedFuture(List.of(mouse())));
        when(inventoryGateway.checkInventory(any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(new StockShortage(PRODUCT_ID, 2L, 1L))));

        assertThatThrownBy(() -> orderService.createOrder(request())).isInstanceOf(OutOfStockException.class);

        verify(orderRepository, never()).save(any());
        verify(inventoryGateway, never()).decreaseInventory(any());
        verify(paymentGateway, never()).pay(anyString(), any());
    }

    @Test
    void createOrder_shouldThrowServiceUnavailable_whenProductServiceDown() {
        when(productGateway.getProductsByProductIds(List.of(PRODUCT_ID))).thenReturn(
                CompletableFuture.failedFuture(new ServiceUnavailableException("product-service", null)));

        assertThatThrownBy(() -> orderService.createOrder(request()))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(orderRepository, never()).save(any());
    }

    @Test
    void createOrder_shouldNotCharge_whenStockDecreaseFails() {
        when(productGateway.getProductsByProductIds(List.of(PRODUCT_ID)))
                .thenReturn(CompletableFuture.completedFuture(List.of(mouse())));
        when(inventoryGateway.checkInventory(any())).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryGateway.decreaseInventory(any())).thenReturn(
                CompletableFuture.failedFuture(new ServiceUnavailableException("inventory-service", null)));

        assertThatThrownBy(() -> orderService.createOrder(request()))
                .isInstanceOf(ServiceUnavailableException.class);

        // The customer must never be charged for stock that could not be reserved
        verify(paymentGateway, never()).pay(anyString(), any());
    }

    private OrderRequest request() {
        return new OrderRequest(1L, List.of(new OrderItemRequest(PRODUCT_ID, 2L)), "MOMO", "TOKEN_OK");
    }

    private ProductResponse mouse() {
        return new ProductResponse(PRODUCT_ID, "Mouse", "Logitech", BigDecimal.valueOf(500_000));
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
