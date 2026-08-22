package com.ecommerce.orderservice.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import com.ecommerce.orderservice.client.ProductClient;
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
import com.ecommerce.orderservice.client.InventoryClient;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class OrderService {

        private final OrderRepository orderRepository;
        private final ProductClient productClient;
        private final InventoryClient inventoryClient;

        // không dùng @Transactional
        // vì transaction DB cục bộ không thể rollback thay đổi ở service khác;
        // giải pháp đúng là Saga, không phải 2PC
        public OrderResponse createOrder(OrderRequest request) {
                // 1. get products
                List<Long> ids = request.items()
                                .stream()
                                .map(OrderItemRequest::productId)
                                .toList();

                Map<Long, ProductResponse> products = productClient.getProductsByProductIds(ids)
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
                List<StockShortage> stockShortages = inventoryClient
                                .checkInventory(new StockCheckRequest(stockCheckItems));
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
                updateInventory(stockCheckItems);

                return toResponse(saved);
        }

        // get all order By User Id
        public List<OrderResponse> getAllOrdersByUserId(Long userId) {
                return orderRepository.findByUserId(userId)
                                .stream()
                                .map(this::toResponse)
                                .toList();
        }

        // TODO: saga
        // update Inventory
        private void updateInventory(List<StockCheckItem> stockCheckItems) {
                inventoryClient.decreaseInventory(new StockCheckRequest(stockCheckItems));
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

}
