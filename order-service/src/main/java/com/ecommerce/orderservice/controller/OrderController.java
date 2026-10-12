package com.ecommerce.orderservice.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.ecommerce.orderservice.dto.OrderRequest;
import com.ecommerce.orderservice.dto.OrderResponse;
import com.ecommerce.orderservice.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * Places and pays an order.
     *
     * <p>The HTTP status is derived from the final order status: {@code 201} when the outcome is
     * known (paid or declined, the result is in the body), {@code 202} when the payment is still
     * unresolved and the client should check the order again later.
     *
     * @param request order and payment details
     * @return the created order
     */
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody OrderRequest request) {
        OrderResponse response = orderService.createOrder(request);
        // No default branch: adding a new OrderStatus must fail compilation until it is mapped here
        HttpStatus status = switch (response.status()) {
            case CONFIRMED, CANCELLED -> HttpStatus.CREATED;
            case PENDING -> HttpStatus.ACCEPTED;
        };
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/user/{userId}")
    public List<OrderResponse> getAllOrdersByUserId(@PathVariable Long userId) {
        return orderService.getAllOrdersByUserId(userId);
    }
}
