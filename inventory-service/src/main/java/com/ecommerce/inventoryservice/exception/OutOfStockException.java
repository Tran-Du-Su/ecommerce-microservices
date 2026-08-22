package com.ecommerce.inventoryservice.exception;

public class OutOfStockException extends RuntimeException {
    public OutOfStockException(Long productId) {
        super("Product out of stock with productId: " + productId);
    }
}
