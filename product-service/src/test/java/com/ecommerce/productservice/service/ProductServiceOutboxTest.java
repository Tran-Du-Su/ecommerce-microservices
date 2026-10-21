package com.ecommerce.productservice.service;

import com.ecommerce.productservice.dto.ProductRequest;
import com.ecommerce.productservice.dto.ProductResponse;
import com.ecommerce.productservice.entity.OutboxEvent;
import com.ecommerce.productservice.entity.Product;
import com.ecommerce.productservice.event.ProductChangedEvent;
import com.ecommerce.productservice.repository.OutboxEventRepository;
import com.ecommerce.productservice.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Verifies that every product change and its outbox event are committed atomically.
 *
 * <p>Deliberately NOT {@code @Transactional}: a test-managed transaction would hide exactly what is
 * under test (commit/rollback of the service's own transaction, and the MANDATORY guard).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProductServiceOutboxTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductEventRecorder eventRecorder;

    @Autowired
    private ProductRepository productRepository;

    @MockitoSpyBean
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void cleanDatabase() {
        outboxEventRepository.deleteAll();
        productRepository.deleteAll();
    }

    @Test
    void createProduct_recordsUnpublishedEventWithInitialVersion() {
        ProductResponse created = productService.createProduct(request("Laptop", "25000000"));

        List<OutboxEvent> events = outboxEvents();
        assertThat(events).hasSize(1);
        OutboxEvent row = events.getFirst();
        assertThat(row.getAggregateType()).isEqualTo("Product");
        assertThat(row.getAggregateId()).isEqualTo(String.valueOf(created.id()));
        assertThat(row.getEventType()).isEqualTo(ProductChangedEvent.TYPE);
        assertThat(row.getPublishedAt()).isNull();

        ProductChangedEvent event = payloadOf(row);
        assertThat(event.productId()).isEqualTo(created.id());
        assertThat(event.name()).isEqualTo("Laptop");
        assertThat(event.price()).isEqualByComparingTo("25000000");
        assertThat(event.version()).isZero();
        assertThat(event.deleted()).isFalse();
    }

    @Test
    void updateProduct_recordsEventCarryingTheNewVersion() {
        ProductResponse created = productService.createProduct(request("Laptop", "25000000"));

        productService.updateProduct(created.id(), request("Laptop", "30000000"));

        List<OutboxEvent> events = outboxEvents();
        assertThat(events).hasSize(2);
        ProductChangedEvent latest = payloadOf(events.getLast());
        assertThat(latest.price()).isEqualByComparingTo("30000000");
        // Must be strictly greater than the create event, or consumers would drop it as a duplicate
        assertThat(latest.version()).isEqualTo(1);
    }

    @Test
    void updateProduct_withoutAnyChange_recordsNoEvent() {
        ProductResponse created = productService.createProduct(request("Laptop", "25000000"));

        productService.updateProduct(created.id(), request("Laptop", "25000000"));

        assertThat(outboxEvents()).hasSize(1);
    }

    @Test
    void deleteProduct_recordsDeletionWithVersionAfterLastChange() {
        ProductResponse created = productService.createProduct(request("Laptop", "25000000")); // v0
        productService.updateProduct(created.id(), request("Laptop", "30000000"));              // v1

        productService.deleteProduct(created.id());

        assertThat(productRepository.existsById(created.id())).isFalse();
        ProductChangedEvent deletion = payloadOf(outboxEvents().getLast());
        assertThat(deletion.deleted()).isTrue();
        assertThat(deletion.version()).isEqualTo(2);
        assertThat(deletion.name()).isNull();
        assertThat(deletion.price()).isNull();
    }

    @Test
    void whenOutboxWriteFails_productChangeIsRolledBack() {
        doThrow(new IllegalStateException("outbox unavailable")).when(outboxEventRepository).save(any());

        assertThatThrownBy(() -> productService.createProduct(request("Laptop", "25000000")))
                .isInstanceOf(IllegalStateException.class);

        // The INSERT into products already ran (IDENTITY) - only the shared transaction can undo it
        assertThat(productRepository.count()).isZero();
    }

    @Test
    void recordingOutsideTransaction_isRejected() {
        Product product = productRepository.save(
                Product.builder().name("Laptop").price(new BigDecimal("25000000")).build());

        assertThatThrownBy(() -> eventRecorder.recordChanged(product))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outboxEvents()).isEmpty();
    }

    private static ProductRequest request(String name, String price) {
        return new ProductRequest(name, "description", new BigDecimal(price));
    }

    private List<OutboxEvent> outboxEvents() {
        return outboxEventRepository.findAll(Sort.by("id"));
    }

    private ProductChangedEvent payloadOf(OutboxEvent row) {
        return jsonMapper.readValue(row.getPayload(), ProductChangedEvent.class);
    }
}
