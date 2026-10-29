package com.ecommerce.orderservice.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event published by product-service on the {@code product.events} topic.
 *
 * <p>Deliberate copy of product-service's contract (no shared module), same as the other DTOs that
 * cross service boundaries: each service can evolve its own classes as long as the JSON stays compatible.
 *
 * @param productId  product id, also the message key
 * @param name       current name ({@code null} when deleted)
 * @param price      current price ({@code null} when deleted)
 * @param version    monotonically increasing per product
 * @param deleted    {@code true} if the product no longer exists
 * @param occurredAt when the change was made in product-service
 */
public record ProductChangedEvent(
        Long productId,
        String name,
        BigDecimal price,
        long version,
        boolean deleted,
        Instant occurredAt) {
}
