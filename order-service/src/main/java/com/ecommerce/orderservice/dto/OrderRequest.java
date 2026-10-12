package com.ecommerce.orderservice.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Request to place an order and pay for it in the same call.
 *
 * @param userId        customer placing the order
 * @param items         ordered products and quantities
 * @param paymentMethod payment method name, forwarded as-is to payment-service, which is the only
 *                      service that knows which methods are supported
 * @param paymentToken  tokenized payment instrument from the client; raw card data never reaches
 *                      the backend
 */
public record OrderRequest(
        @NotNull(message = "User ID is required") Long userId,
        @NotEmpty(message = "Order must have at least one item") List<@Valid OrderItemRequest> items,
        @NotBlank(message = "Payment method is required") String paymentMethod,
        @NotBlank(message = "Payment token is required") String paymentToken) {
}
