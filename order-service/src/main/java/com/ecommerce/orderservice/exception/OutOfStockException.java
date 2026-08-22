package com.ecommerce.orderservice.exception;

import java.util.List;

public class OutOfStockException extends RuntimeException {
    public OutOfStockException(List<Long> productIds) {
        super("Product with ID " + productIds.toString() + " is out of stock");
    }
}
