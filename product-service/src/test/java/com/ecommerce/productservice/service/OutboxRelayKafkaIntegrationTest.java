package com.ecommerce.productservice.service;

import com.ecommerce.productservice.dto.ProductRequest;
import com.ecommerce.productservice.dto.ProductResponse;
import com.ecommerce.productservice.event.ProductChangedEvent;
import com.ecommerce.productservice.repository.OutboxEventRepository;
import com.ecommerce.productservice.repository.ProductRepository;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check of the write side: product change → outbox → relay → real (embedded) Kafka broker.
 *
 * <p>The scheduler is pushed out to one hour so the test drives the relay explicitly and
 * deterministically instead of racing a background thread.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.admin.auto-create=true",
        "outbox.relay.enabled=true",
        "outbox.relay.fixed-delay-ms=3600000"
})
@EmbeddedKafka
class OutboxRelayKafkaIntegrationTest {

    private static final String TOPIC = "product.events";

    @Autowired
    private ProductService productService;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void cleanDatabase() {
        outboxEventRepository.deleteAll();
        productRepository.deleteAll();
    }

    @Test
    void relayedEventsOfOneProduct_shareAPartitionInVersionOrder_andAreNotSentTwice() {
        ProductResponse laptop = productService.createProduct(request("Laptop", "25000000"));
        productService.updateProduct(laptop.id(), request("Laptop", "30000000"));
        ProductResponse phone = productService.createProduct(request("Phone", "10000000"));

        assertThat(relay.relayPendingEvents()).isEqualTo(3);
        assertThat(outboxEventRepository.findAll()).allSatisfy(e -> assertThat(e.getPublishedAt()).isNotNull());
        assertThat(relay.relayPendingEvents()).as("nothing left to resend").isZero();

        List<ConsumerRecord<String, String>> records = consumeAll(3);
        assertThat(records).extracting(ConsumerRecord::key).contains(String.valueOf(phone.id()));
        List<ConsumerRecord<String, String>> laptopRecords = records.stream()
                .filter(r -> r.key().equals(String.valueOf(laptop.id())))
                .toList();
        assertThat(laptopRecords).hasSize(2);
        assertThat(laptopRecords).extracting(ConsumerRecord::partition).containsOnly(laptopRecords.getFirst().partition());
        ProductChangedEvent created = payloadOf(laptopRecords.get(0));
        ProductChangedEvent updated = payloadOf(laptopRecords.get(1));
        assertThat(created.version()).isZero();
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.price()).isEqualByComparingTo("30000000");
        assertThat(laptopRecords.get(1).offset()).isGreaterThan(laptopRecords.get(0).offset());
    }

    @Test
    void topicIsCreatedCompactedWithFixedPartitionCount() throws Exception {
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            TopicDescription description = admin.describeTopics(List.of(TOPIC)).allTopicNames().get().get(TOPIC);
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, TOPIC);
            Config config = admin.describeConfigs(List.of(resource)).all().get().get(resource);

            assertThat(description.partitions()).hasSize(3);
            assertThat(config.get("cleanup.policy").value()).isEqualTo("compact");
        }
    }

    private List<ConsumerRecord<String, String>> consumeAll(int minRecords) {
        Map<String, Object> props = KafkaTestUtils.consumerProps(broker, "outbox-relay-test", false);
        props.put("auto.offset.reset", "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props,
                new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            consumer.subscribe(List.of(TOPIC));
            return StreamSupport.stream(
                            KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), minRecords).spliterator(), false)
                    // records come grouped by partition; within one partition they are in offset order
                    .toList();
        }
    }

    private ProductChangedEvent payloadOf(ConsumerRecord<String, String> record) {
        return jsonMapper.readValue(record.value(), ProductChangedEvent.class);
    }

    private static ProductRequest request(String name, String price) {
        return new ProductRequest(name, "description", new BigDecimal(price));
    }
}
