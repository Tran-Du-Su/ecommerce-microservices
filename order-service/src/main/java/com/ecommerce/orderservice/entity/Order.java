package com.ecommerce.orderservice.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.ecommerce.orderservice.domain.OrderStatus;
import com.ecommerce.orderservice.domain.SagaStep;

@Entity
@Table(name = "orders")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private BigDecimal totalAmount;

    /**
     * Changed only through {@link #confirm()} and {@link #cancel()} so that every transition is
     * validated; the builder is still used to create orders in {@code PENDING}.
     */
    @Setter(AccessLevel.NONE)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    /**
     * Saga progress, written before each remote step (see {@link SagaStep}). Changed only through
     * {@link #markPaymentRequested()}; the builder sets the first step. {@code null} on orders
     * created before this column existed: recovery must treat them as the riskiest case.
     */
    @Setter(AccessLevel.NONE)
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private SagaStep sagaStep;

    private Instant createdAt;

    /**
     * Optimistic lock: every save runs {@code UPDATE ... WHERE id = ? AND version = ?}. When the
     * request and the recovery job both hold this order, the one that saves second matches no row
     * and fails instead of silently overwriting the other's change.
     *
     * <p>{@code DEFAULT 0} because the version column is {@code NOT NULL}: without a default,
     * {@code ddl-auto: update} cannot add it to a table that already has orders, only logs the
     * failure, and every order query then fails on the missing column.
     */
    @Version
    @Setter(AccessLevel.NONE)
    @ColumnDefault("0")
    @Column(nullable = false)
    private Long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items;

    /**
     * Records that the payment request is about to be sent. Must be saved before the call goes out.
     *
     * @throws IllegalStateException if the order is not {@code PENDING} or not at
     *                               {@link SagaStep#RESERVING_STOCK}
     */
    public void markPaymentRequested() {
        requirePending("request payment for");
        // The saga only moves forward: going back to an earlier step would let recovery release
        // stock for an order whose payment may already have gone out
        if (this.sagaStep != SagaStep.RESERVING_STOCK) {
            throw new IllegalStateException("Cannot request payment for order " + id + " at step " + sagaStep);
        }
        this.sagaStep = SagaStep.PAYMENT_REQUESTED;
    }

    /**
     * Claims the order for recovery, before its stock is released. Saved first, it makes the
     * request's own save of {@code PAYMENT_REQUESTED} fail on the version check, so the customer is
     * never charged for an order whose stock is being given back.
     *
     * @throws IllegalStateException if the order is not {@code PENDING} or not at
     *                               {@link SagaStep#RESERVING_STOCK}
     */
    public void markCompensating() {
        requirePending("compensate");
        if (this.sagaStep != SagaStep.RESERVING_STOCK) {
            throw new IllegalStateException("Cannot compensate order " + id + " at step " + sagaStep);
        }
        this.sagaStep = SagaStep.COMPENSATING;
    }

    /**
     * Marks the order as paid.
     *
     * @throws IllegalStateException if the order is not {@code PENDING}
     */
    public void confirm() {
        requirePending("confirm");
        this.status = OrderStatus.CONFIRMED;
    }

    /**
     * Marks the order as cancelled, e.g. after the payment was declined.
     *
     * @throws IllegalStateException if the order is not {@code PENDING}
     */
    public void cancel() {
        requirePending("cancel");
        this.status = OrderStatus.CANCELLED;
    }

    private void requirePending(String action) {
        // CONFIRMED and CANCELLED are terminal: reopening them would desynchronise the order from
        // what really happened to the payment and the stock
        if (this.status != OrderStatus.PENDING) {
            throw new IllegalStateException("Cannot " + action + " order " + id + " in status " + status);
        }
    }
}
