package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.entity.ProductView;
import com.ecommerce.orderservice.event.ProductChangedEvent;
import com.ecommerce.orderservice.repository.ProductViewRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(ProductViewService.class)
class ProductViewServiceTest {

    @Autowired
    private ProductViewService productViewService;

    @Autowired
    private ProductViewRepository productViewRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void apply_firstEvent_insertsTheView() {
        assertThat(productViewService.isEmpty()).isTrue();

        boolean applied = productViewService.apply(event(1L, "25000000", 0));

        assertThat(applied).isTrue();
        assertThat(productViewService.isEmpty()).isFalse();
        assertThat(reload(1L).getPrice()).isEqualByComparingTo("25000000");
    }

    @Test
    void apply_newerEvent_updatesTheStoredView() {
        productViewService.apply(event(1L, "25000000", 0));

        boolean applied = productViewService.apply(event(1L, "30000000", 1));

        assertThat(applied).isTrue();
        ProductView stored = reload(1L);
        assertThat(stored.getPrice()).isEqualByComparingTo("30000000");
        assertThat(stored.getVersion()).isEqualTo(1);
    }

    @Test
    void apply_staleEvent_leavesTheStoredViewUntouched() {
        productViewService.apply(event(1L, "30000000", 1));

        boolean applied = productViewService.apply(event(1L, "25000000", 0));

        assertThat(applied).isFalse();
        assertThat(reload(1L).getPrice()).isEqualByComparingTo("30000000");
    }

    /** Flush pending changes and read the row back from the database, not from the first-level cache. */
    private ProductView reload(Long productId) {
        entityManager.flush();
        entityManager.clear();
        return productViewRepository.findById(productId).orElseThrow();
    }

    private static ProductChangedEvent event(Long id, String price, long version) {
        return new ProductChangedEvent(id, "Laptop", new BigDecimal(price), version, false, Instant.now());
    }
}
