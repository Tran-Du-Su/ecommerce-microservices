package com.ecommerce.inventoryservice.exception;

public class InventoryAlreadyExistsException extends RuntimeException {
    public InventoryAlreadyExistsException(Long productId) {
        super("Inventory already exists for productId: " + productId);
    }
}
