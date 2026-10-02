package com.ecommerce.paymentservice.dto;

import java.math.BigDecimal;

public record ChargeRequest(
        Long orderId,
        BigDecimal amount,
        String currency,
        String paymentToken,
        String idempotencyKey,
        String returnUrl) {

}
