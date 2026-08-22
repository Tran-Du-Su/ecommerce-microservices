package com.ecommerce.inventoryservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record StockCheckItem(@NotNull @Positive Long productId, @NotNull @Positive Long quantity) {

}
