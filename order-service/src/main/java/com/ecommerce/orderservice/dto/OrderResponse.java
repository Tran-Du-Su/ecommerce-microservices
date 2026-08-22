package com.ecommerce.orderservice.dto;

import java.math.BigDecimal;
import java.util.List;

import com.ecommerce.orderservice.domain.OrderStatus;

public record OrderResponse(
                Long id,
                Long userId,
                BigDecimal totalAmount,
                OrderStatus status,
                List<OrderItemResponse> items) {
}
