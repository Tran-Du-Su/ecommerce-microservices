package com.ecommerce.orderservice.dto;

public record StockShortage(
                Long productId,
                Long requested,
                Long available) {

}
