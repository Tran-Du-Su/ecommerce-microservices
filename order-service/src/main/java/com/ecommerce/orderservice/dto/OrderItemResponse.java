package com.ecommerce.orderservice.dto;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long id,
        Long productId,
        String productName,
        Long quantity,
        BigDecimal unitPrice,
        BigDecimal subTotal) {
}
