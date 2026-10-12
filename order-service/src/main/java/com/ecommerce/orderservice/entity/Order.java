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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.ecommerce.orderservice.domain.OrderStatus;

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

    private Instant createdAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items;

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
