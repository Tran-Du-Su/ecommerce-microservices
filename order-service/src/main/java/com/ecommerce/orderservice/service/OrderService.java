package com.ecommerce.orderservice.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import com.ecommerce.orderservice.client.ProductServiceGateway;
import com.ecommerce.orderservice.domain.OrderStatus;
import com.ecommerce.orderservice.dto.OrderRequest;
import com.ecommerce.orderservice.dto.OrderResponse;
import com.ecommerce.orderservice.dto.OrderItemRequest;
import com.ecommerce.orderservice.dto.OrderItemResponse;
import com.ecommerce.orderservice.dto.ProductResponse;
import com.ecommerce.orderservice.dto.StockCheckItem;
import com.ecommerce.orderservice.dto.StockCheckRequest;
import com.ecommerce.orderservice.dto.StockShortage;
import com.ecommerce.orderservice.entity.Order;
import com.ecommerce.orderservice.entity.OrderItem;
import com.ecommerce.orderservice.exception.OutOfStockException;
import com.ecommerce.orderservice.exception.ProductNotFoundException;
import com.ecommerce.orderservice.repository.OrderRepository;
import com.ecommerce.orderservice.client.InventoryServiceGateway;

import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class OrderService {

        private final OrderRepository orderRepository;
        private final ProductServiceGateway productClient;
        private final InventoryServiceGateway inventoryClient;

        // Do not use @Transactional because a local database transaction cannot
        // rollback changes in another service;
        // the correct solution is Saga, not 2PC.
        public OrderResponse createOrder(OrderRequest request) {
                // 1. get products
                List<Long> ids = request.items()
                                .stream()
                                .map(OrderItemRequest::productId)
                                .toList();

                Map<Long, ProductResponse> products = await(productClient.getProductsByProductIds(ids))
                                .stream()
                                .collect(Collectors.toMap(ProductResponse::id, p -> p));

                List<OrderItem> items = new ArrayList<>();
                for (OrderItemRequest req : request.items()) {
                        ProductResponse product = products.get(req.productId());

                        if (product == null)
                                throw new ProductNotFoundException(req.productId());

                        items.add(OrderItem.builder()
                                        .productId(product.id())
                                        .productName(product.name()) // snapshot value
                                        .unitPrice(product.price()) // snapshot value
                                        .quantity(req.quantity())
                                        .build());
                }

                // call inventory, check quantity product
                List<StockCheckItem> stockCheckItems = request.items().stream()
                                .map(item -> new StockCheckItem(item.productId(), item.quantity()))
                                .collect(Collectors.toList());
                List<StockShortage> stockShortages = await(inventoryClient
                                .checkInventory(new StockCheckRequest(stockCheckItems)));
                if (!stockShortages.isEmpty()) {
                        throw new OutOfStockException(stockShortages.stream().map(s -> s.productId()).toList());
                }

                // 3. calculate total + save
                BigDecimal total = items.stream()
                                .map(i -> i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                Order order = Order.builder()
                                .userId(request.userId())
                                .status(OrderStatus.PENDING)
                                .totalAmount(total)
                                .createdAt(Instant.now())
                                .build();
                items.forEach(i -> i.setOrder(order));

                order.setItems(items);

                // save Order
                Order saved = orderRepository.save(order);

                // update Inventory
                await(inventoryClient.decreaseInventory(new StockCheckRequest(stockCheckItems)));

                return toResponse(saved);
        }

        // get all order By User Id
        public List<OrderResponse> getAllOrdersByUserId(Long userId) {
                return orderRepository.findByUserId(userId)
                                .stream()
                                .map(this::toResponse)
                                .toList();
        }

        // mapping entity -> dto response
        private OrderResponse toResponse(Order order) {
                List<OrderItemResponse> itemResponses = order.getItems().stream()
                                .map(item -> new OrderItemResponse(
                                                item.getId(),
                                                item.getProductId(),
                                                item.getProductName(),
                                                item.getQuantity(),
                                                item.getUnitPrice(),
                                                item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()))))
                                .toList();

                return new OrderResponse(
                                order.getId(),
                                order.getUserId(),
                                order.getTotalAmount(),
                                order.getStatus(),
                                itemResponses);
        }

        /**
         * Helper method to await a CompletableFuture and unwrap CompletionException
         * 
         * @param future
         * @return T result
         * @throws T cause if the future completes with an exception
         */
        private <T> T await(CompletableFuture<T> future) {
                try {
                        // join() is a blocking call, so we need to catch CompletionException
                        return future.join();
                } catch (CompletionException e) {
                        // CompletionException is a checked exception, so we need to catch it
                        // and unwrap the cause
                        if (e.getCause() instanceof RuntimeException re) {
                                throw re;
                        }
                        // if the cause is not a RuntimeException, rethrow it as a RuntimeException
                        throw new RuntimeException(e);
                }
        }

}
