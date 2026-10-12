package com.ecommerce.orderservice.dto;

import java.math.BigDecimal;

/**
 * Request body sent to payment-service {@code POST /api/payments}.
 *
 * <p>Intentionally duplicated from payment-service instead of shared through a common module,
 * so the two services can evolve their contracts independently.
 *
 * <p>{@code paymentMethod} is kept as a plain string: order-service only forwards what the client
 * chose, and payment-service remains the single place that knows which methods are supported.
 *
 * @param orderId       order being paid
 * @param amount        final amount to charge
 * @param currency      ISO 4217 currency code
 * @param paymentMethod payment method name, e.g. {@code MOMO}
 * @param paymentToken  tokenized payment instrument provided by the client
 * @param returnUrl     where the payment provider redirects the user after an interactive payment
 */
public record PaymentRequest(
        Long orderId,
        BigDecimal amount,
        String currency,
        String paymentMethod,
        String paymentToken,
        String returnUrl) {
}
