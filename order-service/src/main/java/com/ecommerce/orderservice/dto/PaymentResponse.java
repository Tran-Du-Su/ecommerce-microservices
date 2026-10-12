package com.ecommerce.orderservice.dto;

import java.math.BigDecimal;

import com.ecommerce.orderservice.domain.PaymentStatus;

/**
 * Subset of payment-service's response that order-service actually needs.
 *
 * <p>Fields that order-service does not use are simply not declared; Jackson ignores unknown
 * properties, so payment-service can add fields without breaking this client.
 *
 * @param id            payment id in payment-service
 * @param orderId       order this payment belongs to
 * @param amount        charged amount
 * @param status        outcome of the payment
 * @param transactionId provider transaction id, {@code null} while the outcome is unknown
 * @param failureReason provider's reason when the payment failed
 */
public record PaymentResponse(
        Long id,
        Long orderId,
        BigDecimal amount,
        PaymentStatus status,
        String transactionId,
        String failureReason) {
}
