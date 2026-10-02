package com.ecommerce.orderservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ecommerce.orderservice.client.InventoryServiceGateway;
import com.ecommerce.orderservice.client.ProductServiceGateway;
import com.ecommerce.orderservice.dto.OrderItemRequest;
import com.ecommerce.orderservice.dto.OrderRequest;
import com.ecommerce.orderservice.dto.OrderResponse;
import com.ecommerce.orderservice.dto.ProductResponse;
import com.ecommerce.orderservice.dto.StockShortage;
import com.ecommerce.orderservice.entity.Order;
import com.ecommerce.orderservice.exception.OutOfStockException;
import com.ecommerce.orderservice.exception.ProductNotFoundException;
import com.ecommerce.orderservice.exception.ServiceUnavailableException;
import com.ecommerce.orderservice.repository.OrderRepository;

// Test By Mockito
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    // Mock objects
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductServiceGateway productGateway;
    @Mock
    private InventoryServiceGateway inventoryGateway;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, productGateway, inventoryGateway);
    }

    // Unit test create order
    @Test
    void createorder_shouldSuccess_whenProductAndStockSufficient() {
        // Arrange
        OrderRequest request = new OrderRequest(1L, List.of(new OrderItemRequest(100L, 2L)));
        // Mock data product
        ProductResponse product = new ProductResponse(100L, "Mouse", "Logitech", BigDecimal.valueOf(500_000));

        // stub / mock behavior get product
        when(productGateway.getProductsByProductIds(List.of(100L)))
                .thenReturn(CompletableFuture.completedFuture(List.of(product)));

        // stub / mock behavior check inventory
        when(inventoryGateway.checkInventory(any())).thenReturn(CompletableFuture.completedFuture(List.of()));

        // stub / mock behavior save order
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(1L);
            return order;
        });

        // Act
        OrderResponse response = orderService.createOrder(request);

        // Assert
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.totalAmount()).isEqualByComparingTo("1000000");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).productName()).isEqualTo("Mouse");

        // verify behavior
        verify(inventoryGateway).decreaseInventory(any());
    }

    @Test
    void createOrder_shouldThrow_whenProductNotFound() {
        OrderRequest request = new OrderRequest(1L, List.of(new OrderItemRequest(999L, 1L)));

        when(productGateway.getProductsByProductIds(List.of(999L)))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        assertThatThrownBy(() -> orderService.createOrder(request)).isInstanceOf(ProductNotFoundException.class);

        verify(orderRepository, never()).save(any());
        verify(inventoryGateway, never()).decreaseInventory(any());
    }

    @Test
    void createOrder_shouldThrow_whenOutOfStock() {
        // Arrange
        OrderRequest request = new OrderRequest(1L, List.of(new OrderItemRequest(100L, 2L)));
        // Mock data product
        ProductResponse product = new ProductResponse(100L, "Mouse", "Logitech", BigDecimal.valueOf(500_000));

        // mock data product
        when(productGateway.getProductsByProductIds(List.of(100L)))
                .thenReturn(CompletableFuture.completedFuture(List.of(product)));

        // mock data inventory
        when(inventoryGateway.checkInventory(any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(new StockShortage(100L, 2L, 1L))));

        // Action and Assert
        assertThatThrownBy(() -> orderService.createOrder(request)).isInstanceOf(OutOfStockException.class);

        // verify behavior
        verify(orderRepository, never()).save(any());
        verify(inventoryGateway, never()).decreaseInventory(any());
    }

    @Test
    void createOrder_shouldThrowServiceUnavailable_whenProductServiceDown() {
        // Arrange
        OrderRequest request = new OrderRequest(1L, List.of(new OrderItemRequest(100L, 2L)));

        // mock data product
        when(productGateway.getProductsByProductIds(List.of(100L)))
                .thenReturn(CompletableFuture.failedFuture(new ServiceUnavailableException("product-service", null)));

        // Action and Assert
        assertThatThrownBy(() -> orderService.createOrder(request)).isInstanceOf(ServiceUnavailableException.class);

        // verify behavior
        verify(orderRepository, never()).save(any());
    }

}
