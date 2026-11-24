package com.ecommerce.inventoryservice.exception;

/**
 * The order was compensated before its reserve arrived: the release left a tombstone, and the late
 * reserve must not take stock for an order nobody will ever release again.
 */
public class ReservationReleasedException extends RuntimeException {
    public ReservationReleasedException(Long orderId) {
        super("Order " + orderId + " was already released; its stock can no longer be reserved");
    }
}
