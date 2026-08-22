package com.ecommerce.inventoryservice.dto;

public record StockShortage(Long productId, Long requested, Long available) {

}
