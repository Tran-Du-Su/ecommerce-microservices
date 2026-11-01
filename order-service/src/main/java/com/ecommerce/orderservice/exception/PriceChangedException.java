package com.ecommerce.orderservice.exception;

import java.util.List;

import lombok.Getter;

/**
 * The price the client showed differs from the price in the local product view.
 *
 * <p>Carries only product ids, never our price: the view may be the stale side (e.g. events delayed
 * by a Kafka outage), so the client must reload prices from product-service, the source of truth.
 */
@Getter
public class PriceChangedException extends RuntimeException {

    private final List<Long> productIds;

    public PriceChangedException(List<Long> productIds) {
        super("Prices changed for products " + productIds + ". Reload them and confirm the order again.");
        this.productIds = productIds;
    }
}
