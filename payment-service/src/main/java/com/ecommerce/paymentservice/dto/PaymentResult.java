package com.ecommerce.paymentservice.dto;

import java.time.Instant;

import com.ecommerce.paymentservice.domain.PaymentStatus;

public record PaymentResult(
                String transactionId,
                PaymentStatus status,
                String redirectUrl,
                String failureReason,
                Instant processedAt) {

}
