package com.ecommerce.productservice.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Public contract published whenever a product is created, updated or deleted.
 *
 * <p>Event-Carried State Transfer: the event holds the full state consumers need, so they can keep
 * a local copy without calling product-service back. Consumers apply an event only if its
 * {@code version} is greater than the one they hold, which makes them safe against duplicates and
 * out-of-order delivery.
 *
 * @param productId  product id, also the message key
 * @param name       current name ({@code null} when deleted)
 * @param price      current price ({@code null} when deleted)
 * @param version    monotonically increasing per product
 * @param deleted    {@code true} if the product no longer exists
 * @param occurredAt when the change was made
 */
public record ProductChangedEvent(
        Long productId,
        String name,
        BigDecimal price,
        long version,
        boolean deleted,
        Instant occurredAt) {

    public static final String TYPE = "ProductChanged";
}
