package com.ecommerce.productservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record ProductRequest(
        @NotBlank(message = "Product name cannot be empty") String name,

        String description,

        @NotNull(message = "Price cannot be empty") @Positive(message = "Price must be greater than 0") BigDecimal price) {
}
