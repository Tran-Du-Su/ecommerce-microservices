package com.ecommerce.orderservice.messaging;

import com.ecommerce.orderservice.entity.ProductView;
import com.ecommerce.orderservice.event.ProductChangedEvent;
import com.ecommerce.orderservice.exception.MalformedEventException;
import com.ecommerce.orderservice.repository.ProductViewRepository;
import com.ecommerce.orderservice.service.ProductViewService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Runs the real listener, error handler and dead-letter publishing against an
 * embedded broker.
 *
 * <p>
 * Records are sent to an explicit partition, followed by a "sentinel" event on
 * the same partition:
 * once the sentinel is visible, everything sent before it on that partition has
 * been processed
 * (one partition = one thread, in offset order). Tests use distinct product ids
 * instead of cleaning
 * the database, because cleaning it would trigger the empty-database re-read
 * under test below.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.admin.auto-create=true",
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.consumer.group-id=order-service-product-view-test",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "product-events.retry-initial-interval=50ms",
        "product-events.retry-max-interval=100ms"
})
@EmbeddedKafka(partitions = 3, topics = "product.events")
class ProductEventListenerKafkaIntegrationTest {

    private static final String TOPIC = "product.events";
    private static final String DLT = "product.events.order-service.DLT";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ProductViewRepository productViewRepository;

    /**
     * Spy on the service (a concrete class): a Spring Data repository is an
     * interface proxy with no real method to call.
     */
    @MockitoSpyBean
    private ProductViewService productViewService;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Test
    void newerEventsWin_andStaleRedeliveryIsIgnored() {
        send(0, 101, json(101, "Laptop", 25_000_000, 0));
        send(0, 101, json(101, "Laptop", 30_000_000, 1));
        send(0, 101, json(101, "Laptop", 25_000_000, 0)); // late duplicate of v0
        awaitSentinel(0, 1001);

        ProductView view = productViewRepository.findById(101L).orElseThrow();
        assertThat(view.getVersion()).isEqualTo(1);
        assertThat(view.getPrice()).isEqualByComparingTo("30000000");
    }

    @Test
    void poisonMessage_goesToDeadLetterTopic_andThePartitionKeepsFlowing() {
        send(1, 202, "{not json");
        send(1, 202, json(202, "Phone", 10_000_000, 0));

        awaitView(202L);
        List<ConsumerRecord<String, String>> deadLetters = deadLettersWithKey("202");
        assertThat(deadLetters).hasSize(1);
        ConsumerRecord<String, String> deadLetter = deadLetters.getFirst();
        assertThat(deadLetter.value()).isEqualTo("{not json");
        assertThat(deadLetter.partition()).as("same partition as the source record").isEqualTo(1);
        // The listener's exception is wrapped by the container; the root cause is in
        // its own header
        assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .isEqualTo(MalformedEventException.class.getName());
    }

    @Test
    void readableEventBreakingTheContract_isAlsoDeadLettered() {
        // Valid JSON, but a live product without a price: applying it would put a null
        // price in the cache
        send(1, 505, """
                {"productId":505,"name":"Camera","version":0,"deleted":false}""");
        send(1, 505, json(505, "Camera", 7_000_000, 0));

        awaitView(505L);
        assertThat(deadLettersWithKey("505")).hasSize(1);
        assertThat(productViewRepository.findById(505L).orElseThrow().getPrice()).isEqualByComparingTo("7000000");
    }

    @Test
    void transientFailure_isRetriedUntilItSucceeds_notDeadLettered() {
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .doThrow(new DataAccessResourceFailureException("database unavailable"))
                .doThrow(new DataAccessResourceFailureException("database unavailable"))
                .doCallRealMethod()
                .when(productViewService).apply(argThat(event -> event.productId() == 303L));

        send(2, 303, json(303, "Tablet", 8_000_000, 0));

        awaitView(303L);
        verify(productViewService, atLeast(4)).apply(argThat((ProductChangedEvent event) -> event.productId() == 303L));
        assertThat(deadLettersWithKey("303")).isEmpty();
    }

    @Test
    void wipedDatabase_isRebuiltFromTheBeginningOfTheTopic() {
        send(0, 404, json(404, "Monitor", 5_000_000, 0));
        awaitView(404L);

        // Simulate a deleted database file while the group's offsets survive in Kafka,
        // then force a new partition assignment (what a restart does)
        listenerRegistry.getListenerContainers().forEach(container -> container.stop());
        productViewRepository.deleteAll();
        listenerRegistry.getListenerContainers().forEach(container -> container.start());

        // Without the re-read, consumption resumes after the committed offsets and 404
        // never comes back
        awaitView(404L);
    }

    private void send(int partition, long key, String value) {
        kafkaTemplate.send(TOPIC, partition, String.valueOf(key), value).join();
    }

    private void awaitSentinel(int partition, long sentinelId) {
        send(partition, sentinelId, json(sentinelId, "sentinel", 1, 0));
        awaitView(sentinelId);
    }

    private ProductView awaitView(Long productId) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<ProductView> view = productViewRepository.findById(productId);
            if (view.isPresent()) {
                return view.get();
            }
            sleep(50);
        }
        throw new AssertionError("Product view " + productId + " did not appear within " + TIMEOUT);
    }

    private List<ConsumerRecord<String, String>> deadLettersWithKey(String key) {
        Map<String, Object> props = KafkaTestUtils.consumerProps(broker, "dlt-reader-" + UUID.randomUUID(), false);
        props.put("auto.offset.reset", "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props,
                new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            consumer.subscribe(List.of(DLT));
            // A short poll window is enough: dead letters are published before the next
            // record is processed
            return StreamSupport.stream(KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(3)).spliterator(), false)
                    .filter(record -> key.equals(record.key()))
                    .toList();
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static String json(long id, String name, long price, long version) {
        return """
                {"productId":%d,"name":"%s","price":%d,"version":%d,"deleted":false,"occurredAt":"2026-10-21T00:00:00Z"}"""
                .formatted(id, name, price, version);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
