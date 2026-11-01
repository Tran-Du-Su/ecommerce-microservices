package com.ecommerce.orderservice.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * One line of an order.
 *
 * @param productId     product to buy
 * @param quantity      number of units
 * @param expectedPrice unit price the client showed to the customer. Required: the order is rejected
 *                      if it differs from the price we would charge, so the customer is never charged
 *                      a price they did not see
 */
public record OrderItemRequest(
                @NotNull(message = "Product ID is required") Long productId,
                @Positive(message = "Quantity must be positive") Long quantity,
                @NotNull(message = "Expected price is required")
                @Positive(message = "Expected price must be positive") BigDecimal expectedPrice) {
}
