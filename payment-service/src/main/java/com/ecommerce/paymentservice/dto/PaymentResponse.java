package com.ecommerce.paymentservice.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.ecommerce.paymentservice.domain.PaymentMethod;
import com.ecommerce.paymentservice.domain.PaymentStatus;

public record PaymentResponse(
        Long id,
        String idempotencyKey,
        Long orderId,
        BigDecimal amount,
        String currency,
        PaymentMethod paymentMethod,
        PaymentStatus status,
        String transactionId,
        String failureReason,
        Instant createdAt,
        Instant processedAt) {

}
