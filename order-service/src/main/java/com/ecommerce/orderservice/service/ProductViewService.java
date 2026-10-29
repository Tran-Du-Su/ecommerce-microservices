package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.entity.ProductView;
import com.ecommerce.orderservice.event.ProductChangedEvent;
import com.ecommerce.orderservice.repository.ProductViewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the local {@link ProductView} read model in sync with product-service events.
 */
@Service
@RequiredArgsConstructor
public class ProductViewService {

    private final ProductViewRepository productViewRepository;

    /**
     * Inserts or updates the view of the event's product, ignoring duplicate and stale events.
     *
     * <p>A plain read-check-write is safe here (unlike stock decrements in inventory-service):
     * every event of a product has the same key, hence the same partition, and a partition is
     * consumed by exactly one listener thread per instance, so no two writers race on one product.
     *
     * @param event the received event
     * @return {@code true} if the view changed
     */
    @Transactional
    public boolean apply(ProductChangedEvent event) {
        return productViewRepository.findById(event.productId())
                .map(view -> view.apply(event)) // managed entity: dirty checking flushes it on commit
                .orElseGet(() -> {
                    productViewRepository.save(ProductView.from(event));
                    return true;
                });
    }

    /**
     * @return {@code true} if no product has been received yet (e.g. the database was wiped)
     */
    @Transactional(readOnly = true)
    public boolean isEmpty() {
        return productViewRepository.count() == 0;
    }
}
