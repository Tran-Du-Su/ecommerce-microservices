package com.ecommerce.orderservice.entity;

import com.ecommerce.orderservice.event.ProductChangedEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductViewTest {

    @Test
    void from_copiesTheEventState() {
        ProductView view = ProductView.from(changed(1L, "Laptop", "25000000", 0));

        assertThat(view.getProductId()).isEqualTo(1L);
        assertThat(view.getName()).isEqualTo("Laptop");
        assertThat(view.getPrice()).isEqualByComparingTo("25000000");
        assertThat(view.getVersion()).isZero();
        assertThat(view.isDeleted()).isFalse();
    }

    @Test
    void apply_newerVersion_updatesTheView() {
        ProductView view = ProductView.from(changed(1L, "Laptop", "25000000", 0));

        boolean applied = view.apply(changed(1L, "Laptop", "30000000", 1));

        assertThat(applied).isTrue();
        assertThat(view.getPrice()).isEqualByComparingTo("30000000");
        assertThat(view.getVersion()).isEqualTo(1);
    }

    @Test
    void apply_sameVersion_isIgnoredAsDuplicate() {
        ProductView view = ProductView.from(changed(1L, "Laptop", "25000000", 3));

        boolean applied = view.apply(changed(1L, "Laptop (changed)", "99", 3));

        assertThat(applied).isFalse();
        assertThat(view.getName()).isEqualTo("Laptop");
        assertThat(view.getPrice()).isEqualByComparingTo("25000000");
    }

    @Test
    void apply_olderVersion_isIgnoredAsStale() {
        ProductView view = ProductView.from(changed(1L, "Laptop", "30000000", 4));

        boolean applied = view.apply(changed(1L, "Laptop", "28000000", 3));

        assertThat(applied).isFalse();
        assertThat(view.getPrice()).isEqualByComparingTo("30000000");
        assertThat(view.getVersion()).isEqualTo(4);
    }

    @Test
    void apply_deletion_marksDeletedAndKeepsLastKnownNameAndPrice() {
        ProductView view = ProductView.from(changed(1L, "Laptop", "30000000", 1));

        boolean applied = view.apply(deleted(1L, 2));

        assertThat(applied).isTrue();
        assertThat(view.isDeleted()).isTrue();
        assertThat(view.getVersion()).isEqualTo(2);
        assertThat(view.getName()).isEqualTo("Laptop");
    }

    @Test
    void apply_lateEventAfterDeletion_doesNotResurrectTheProduct() {
        ProductView view = ProductView.from(changed(1L, "Laptop", "30000000", 1));
        view.apply(deleted(1L, 2));

        boolean applied = view.apply(changed(1L, "Laptop", "30000000", 1));

        assertThat(applied).isFalse();
        assertThat(view.isDeleted()).isTrue();
    }

    @Test
    void from_deletionAsFirstEvent_createsDeletedViewWithoutNameOrPrice() {
        // After compaction a deleted product may be represented by its deletion event only
        ProductView view = ProductView.from(deleted(1L, 5));

        assertThat(view.isDeleted()).isTrue();
        assertThat(view.getVersion()).isEqualTo(5);
        assertThat(view.getName()).isNull();
        assertThat(view.getPrice()).isNull();
    }

    @Test
    void apply_eventOfAnotherProduct_isRejected() {
        ProductView view = ProductView.from(changed(1L, "Laptop", "25000000", 0));

        assertThatThrownBy(() -> view.apply(changed(2L, "Phone", "10000000", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ProductChangedEvent changed(Long id, String name, String price, long version) {
        return new ProductChangedEvent(id, name, new BigDecimal(price), version, false, Instant.now());
    }

    private static ProductChangedEvent deleted(Long id, long version) {
        return new ProductChangedEvent(id, null, null, version, true, Instant.now());
    }
}
