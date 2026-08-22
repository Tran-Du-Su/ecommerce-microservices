package com.ecommerce.inventoryservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record InventoryRequest(

        @NotNull(message = "Product ID is required") Long productId,

        @NotNull(message = "Quantity is required") @Positive(message = "Quantity must be greater than 0") Long quantity) {
}
