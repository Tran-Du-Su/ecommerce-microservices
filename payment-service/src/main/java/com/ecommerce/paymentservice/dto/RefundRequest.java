package com.ecommerce.paymentservice.dto;

import java.math.BigDecimal;

public record RefundRequest(
                Long orderId,
                String transactionId,
                BigDecimal amount,
                String idempotencyKey) {

}
