package com.ecommerce.orderservice.exception;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(Long id) {
        super("Product with ID not found: " + id);
    }
}
