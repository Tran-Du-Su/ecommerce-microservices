package com.ecommerce.productservice.service;

import com.ecommerce.productservice.entity.OutboxEvent;
import com.ecommerce.productservice.entity.Product;
import com.ecommerce.productservice.event.ProductChangedEvent;
import com.ecommerce.productservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

/**
 * Records {@link ProductChangedEvent}s into the outbox table.
 *
 * <p>{@code MANDATORY} propagation: an outbox row written in its own transaction would bring back
 * the dual-write problem (product committed, event lost, or the other way round). Requiring an
 * existing transaction turns a forgotten {@code @Transactional} on the caller into an immediate
 * {@code IllegalTransactionStateException} instead of a silent inconsistency.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ProductEventRecorder {

    private static final String AGGREGATE_TYPE = "Product";

    private final OutboxEventRepository outboxEventRepository;
    private final JsonMapper jsonMapper;

    /**
     * Records the current state of a product. The product must already be flushed so that its
     * {@code version} reflects the change being recorded.
     *
     * @param product the created or updated product
     */
    public void recordChanged(Product product) {
        record(new ProductChangedEvent(product.getId(), product.getName(), product.getPrice(),
                product.getVersion(), false, Instant.now()));
    }

    /**
     * Records the deletion of a product.
     *
     * @param product the product being deleted, as loaded before deletion
     */
    public void recordDeleted(Product product) {
        // A hard delete leaves no row to bump the version on, but consumers only apply strictly newer
        // versions: reusing the last version would make them drop the deletion as a duplicate.
        record(new ProductChangedEvent(product.getId(), null, null,
                product.getVersion() + 1, true, Instant.now()));
    }

    private void record(ProductChangedEvent event) {
        outboxEventRepository.save(OutboxEvent.create(AGGREGATE_TYPE, String.valueOf(event.productId()),
                ProductChangedEvent.TYPE, jsonMapper.writeValueAsString(event)));
    }
}
