package com.ecommerce.orderservice.domain;

/**
 * How far the order saga got. Internal state for recovery, never exposed through the API: the
 * public {@link OrderStatus} stays {@code PENDING} for both values.
 *
 * <p>Each value is committed BEFORE the remote call it names. The database can then only be
 * "behind" in the safe direction: it may claim a step that never went out, never miss one that did.
 */
public enum SagaStep {

    /** Stock may be held, no payment was ever requested: recovery may release and cancel. */
    RESERVING_STOCK,

    /** The customer may have been charged: recovery must ask payment-service before deciding. */
    PAYMENT_REQUESTED
}
