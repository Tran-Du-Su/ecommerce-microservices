package com.ecommerce.orderservice.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record OrderRequest(
        @NotNull(message = "User ID is required") Long userId,
        @NotEmpty(message = "Order must have at least one item") List<@Valid OrderItemRequest> items) {
}
