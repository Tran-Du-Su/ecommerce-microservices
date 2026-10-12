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
import com.ecommerce.orderservice.client.PaymentServiceGateway;
import com.ecommerce.orderservice.dto.PaymentRequest;
import com.ecommerce.orderservice.dto.PaymentResponse;
import com.ecommerce.orderservice.exception.ServiceUnavailableException;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

        /** Orders have no currency of their own yet; the shop sells in VND only. */
        private static final String CURRENCY = "VND";

        private final OrderRepository orderRepository;
        private final ProductServiceGateway productGateway;
        private final InventoryServiceGateway inventoryGateway;
        private final PaymentServiceGateway paymentGateway;

        /**
         * Places an order: snapshot products, check stock, save the order as {@code PENDING},
         * decrease stock, then charge the customer.
         *
         * <p>The charge is the last step on purpose: it is the hardest step to undo (a refund goes
         * through a third party and is visible to the customer), so every failure that can happen
         * before it only needs cheap, internal compensation.
         *
         * <p>Not {@code @Transactional}: a local transaction cannot roll back changes made in other
         * services. Cross-service consistency is the job of a Saga (Part 8).
         *
         * @param request order and payment details
         * @return the order with its final status: {@code CONFIRMED} (paid), {@code CANCELLED}
         *         (payment declined) or {@code PENDING} (payment outcome unknown)
         */
        public OrderResponse createOrder(OrderRequest request) {
                // 1. get products
                List<Long> ids = request.items()
                                .stream()
                                .map(OrderItemRequest::productId)
                                .toList();

                Map<Long, ProductResponse> products = await(productGateway.getProductsByProductIds(ids))
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
                List<StockShortage> stockShortages = await(inventoryGateway
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

                // Saved before calling other services: the order id is needed to build the payment
                // idempotency key
                Order saved = orderRepository.save(order);

                await(inventoryGateway.decreaseInventory(new StockCheckRequest(stockCheckItems)));

                return toResponse(chargeAndApplyOutcome(saved, request));
        }

        /**
         * Charges the order and moves it to the status matching the payment outcome.
         *
         * <p>Known gap (deferred to Part 8): when the payment is declined the order is cancelled but
         * the stock decreased earlier is NOT released, because inventory-service has no release API
         * yet.
         *
         * @param order   saved order in {@code PENDING}
         * @param request original request carrying the payment method and token
         * @return the order after the outcome has been applied and persisted
         */
        private Order chargeAndApplyOutcome(Order order, OrderRequest request) {
                // Derived from the order id rather than random: it can be recomputed after a crash,
                // so a later retry or reconciliation reuses the same key and cannot charge twice.
                // Safe only because a declined payment cancels the order (no second attempt on the
                // same order); allowing re-payment would require one key per attempt.
                String idempotencyKey = "order-" + order.getId();
                PaymentRequest paymentRequest = new PaymentRequest(order.getId(), order.getTotalAmount(), CURRENCY,
                                request.paymentMethod(), request.paymentToken(), null);

                PaymentResponse payment;
                try {
                        payment = await(paymentGateway.pay(idempotencyKey, paymentRequest));
                } catch (ServiceUnavailableException e) {
                        // No answer from payment-service: the charge may or may not have happened.
                        // Cancelling here could leave a customer charged for a cancelled order, so the
                        // order stays PENDING until the outcome is known.
                        log.warn("Payment outcome unknown for order {}, keeping it PENDING", order.getId(), e);
                        return order;
                } catch (FeignException.FeignClientException e) {
                        // payment-service rejected the request (e.g. unsupported payment method), so no
                        // money was taken: the order can safely be closed before reporting the error
                        order.cancel();
                        orderRepository.save(order);
                        throw e;
                }

                switch (payment.status()) {
                        case SUCCESS -> order.confirm();
                        case FAILED -> order.cancel();
                        case PENDING -> {
                                // The provider has not decided yet; the order waits like the payment does
                        }
                        // A brand-new order cannot already be refunded; reaching this means a bug or a
                        // reused key, so surface it instead of guessing a status
                        case REFUNDED -> throw new IllegalStateException(
                                        "Unexpected REFUNDED payment for new order " + order.getId());
                }
                return orderRepository.save(order);
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
