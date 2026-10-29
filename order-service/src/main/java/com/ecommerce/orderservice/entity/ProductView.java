package com.ecommerce.orderservice.entity;

import com.ecommerce.orderservice.event.ProductChangedEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Local read model of a product, built from {@link ProductChangedEvent}s (a cache, not a record of
 * history: order items still snapshot name and price at order time).
 *
 * <p>Only {@link #from} and {@link #apply} change it, and both obey one rule: a strictly newer
 * {@code version} wins. That single rule makes the consumer safe against duplicates (same version)
 * and late, out-of-order deliveries (older version).
 */
@Entity
@Table(name = "product_views")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA only
public class ProductView {

    /** The product-service id, not generated here: this row mirrors a product owned elsewhere. */
    @Id
    private Long productId;

    // Nullable on purpose: after log compaction the only event left for a deleted product may be
    // the deletion itself, which carries no name or price.
    private String name;

    @Column(precision = 19, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private long version;

    /**
     * Soft delete: the row is kept so its version still blocks an older event that arrives late
     * from bringing the product back.
     */
    @Column(nullable = false)
    private boolean deleted;

    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Creates the view from the first event seen for a product.
     *
     * @param event any event, including a deletion
     * @return a new, not yet persisted view
     */
    public static ProductView from(ProductChangedEvent event) {
        ProductView view = new ProductView();
        view.productId = event.productId();
        view.copyStateFrom(event);
        return view;
    }

    /**
     * Applies the event if it is newer than the current state.
     *
     * @param event an event for this product
     * @return {@code true} if applied, {@code false} if it was a duplicate or stale and was ignored
     */
    public boolean apply(ProductChangedEvent event) {
        if (!productId.equals(event.productId())) {
            throw new IllegalArgumentException("Event for product " + event.productId()
                    + " applied to view of product " + productId);
        }
        // Strictly greater: an equal version is a redelivery of what we already hold
        if (event.version() <= this.version) {
            return false;
        }
        copyStateFrom(event);
        return true;
    }

    private void copyStateFrom(ProductChangedEvent event) {
        if (!event.deleted()) {
            this.name = event.name();
            this.price = event.price();
        }
        // On deletion keep the last known name/price: useful for debugging, never used for ordering
        this.deleted = event.deleted();
        this.version = event.version();
        this.updatedAt = Instant.now();
    }
}
