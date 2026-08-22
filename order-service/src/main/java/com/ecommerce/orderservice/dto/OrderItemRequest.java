package com.ecommerce.orderservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record OrderItemRequest(
                @NotNull(message = "Product ID is required") Long productId,
                @Positive(message = "Quantity must be positive") Long quantity) {
}
