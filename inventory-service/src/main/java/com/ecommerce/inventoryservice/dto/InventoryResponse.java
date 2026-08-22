package com.ecommerce.inventoryservice.dto;

public record InventoryResponse(
        Long id,
        Long productId,
        Long quantity) {
}
