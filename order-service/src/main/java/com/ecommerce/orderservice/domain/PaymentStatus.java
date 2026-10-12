package com.ecommerce.orderservice.domain;

/**
 * Payment outcome as reported by payment-service.
 *
 * <p>Duplicated on purpose (no shared module between services). Must stay a superset of the values
 * payment-service can return, otherwise deserialization of its response fails.
 */
public enum PaymentStatus {
    /** Outcome unknown yet; the charge may or may not have happened. */
    PENDING,
    /** Money was taken. */
    SUCCESS,
    /** Provider explicitly declined; no money was taken. */
    FAILED,
    /** Money was taken and later returned. */
    REFUNDED
}
