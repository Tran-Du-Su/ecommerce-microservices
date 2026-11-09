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
 * Stock taken for one product of one order: the record that makes both Saga steps idempotent.
 *
 * <p>Reserve inserts it, and the unique key turns a repeated reserve into a no-op. Release flips
 * its status once, and a repeated release finds nothing left to flip. Inventory, not the caller,
 * remembers what was taken, so a release can never return a different quantity.
 */
@Entity
@Table(name = "stock_reservations", uniqueConstraints = @UniqueConstraint(
        name = "uk_stock_reservations_order_product", columnNames = { "order_id", "product_id" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA only
public class StockReservation {

    public enum Status {
        RESERVED,
        RELEASED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Long quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(nullable = false)
    private Instant createdAt;

    /**
     * @return a new reservation in {@code RESERVED}, not yet persisted
     */
    public static StockReservation reserve(Long orderId, Long productId, Long quantity) {
        StockReservation reservation = new StockReservation();
        reservation.orderId = orderId;
        reservation.productId = productId;
        reservation.quantity = quantity;
        reservation.status = Status.RESERVED;
        reservation.createdAt = Instant.now();
        return reservation;
    }
}
