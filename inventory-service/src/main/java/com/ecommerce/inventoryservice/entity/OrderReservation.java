package com.ecommerce.inventoryservice.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One row per order: what inventory decided for the whole order, next to the per-product
 * {@link StockReservation} rows.
 *
 * <p>Its unique {@code order_id} is the single key that every Saga call for an order writes
 * first. Release only knows the order id, not the products, so the per-product key cannot serve
 * that purpose. Two calls inserting the same order id are queued by the database: the second waits
 * for the first transaction to end, then fails as a duplicate if it committed.
 */
@Entity
@Table(name = "order_reservations", uniqueConstraints = @UniqueConstraint(
        name = "uk_order_reservations_order", columnNames = "order_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA only
public class OrderReservation {

    public enum Status {
        RESERVED,
        RELEASED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    // varchar, not a database ENUM: a value added later must not need a schema change
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(16)")
    private Status status;

    @Column(nullable = false)
    private Instant createdAt;

    /**
     * @return a new row in {@code RESERVED}, not yet persisted
     */
    public static OrderReservation reserved(Long orderId) {
        return of(orderId, Status.RESERVED);
    }

    /**
     * A tombstone: the order was released before anything was reserved for it. Kept so that a
     * reserve arriving late is refused instead of holding stock that no release will ever return.
     *
     * @return a new row in {@code RELEASED}, not yet persisted
     */
    public static OrderReservation tombstone(Long orderId) {
        return of(orderId, Status.RELEASED);
    }

    private static OrderReservation of(Long orderId, Status status) {
        OrderReservation reservation = new OrderReservation();
        reservation.orderId = orderId;
        reservation.status = status;
        reservation.createdAt = Instant.now();
        return reservation;
    }
}
