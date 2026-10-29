package com.ecommerce.orderservice.messaging;

import com.ecommerce.orderservice.event.ProductChangedEvent;
import com.ecommerce.orderservice.exception.MalformedEventException;
import com.ecommerce.orderservice.service.ProductViewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.AbstractConsumerSeekAware;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Consumes product events and keeps the local {@code ProductView} read model in sync.
 *
 * <p>Every instance reads the whole topic (broadcast): each instance has its own database, so
 * sharing a consumer group would split the partitions and leave each instance with only part of
 * the catalogue. The group id is therefore per instance (see {@code app.instance-id}).
 *
 * <p>Offsets are committed by the container after this method returns, i.e. after the database
 * transaction committed: a crash in between redelivers the event, which the version check ignores.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventListener extends AbstractConsumerSeekAware {

    private final ProductViewService productViewService;
    private final JsonMapper jsonMapper;

    /**
     * Applies one product event. Throws {@link MalformedEventException} for poison messages (sent
     * to the dead-letter topic without retrying); any other exception is retried with back-off.
     *
     * @param record raw record; the value is JSON text
     */
    @KafkaListener(topics = "${product-events.topic:product.events}")
    public void onProductChanged(ConsumerRecord<String, String> record) {
        ProductChangedEvent event = parse(record);
        boolean applied = productViewService.apply(event);
        log.debug("Product event productId={} version={} partition={} offset={} applied={}",
                event.productId(), event.version(), record.partition(), record.offset(), applied);
    }

    /**
     * Safety net for a wiped database: committed offsets live in Kafka, the data lives in our own
     * database, and nothing ties the two together. If the read model is empty while the group
     * still has offsets, resuming from them would leave the cache empty forever, so re-read the
     * topic from the beginning instead (cheap: the topic is compacted).
     */
    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        super.onPartitionsAssigned(assignments, callback);
        if (productViewService.isEmpty()) {
            log.info("Product read model is empty, re-reading partitions {} from the beginning", assignments.keySet());
            callback.seekToBeginning(assignments.keySet());
        }
    }

    private ProductChangedEvent parse(ConsumerRecord<String, String> record) {
        if (record.value() == null) {
            throw new MalformedEventException("Empty product event at offset " + record.offset());
        }
        ProductChangedEvent event;
        try {
            event = jsonMapper.readValue(record.value(), ProductChangedEvent.class);
        } catch (JacksonException e) {
            throw new MalformedEventException("Unreadable product event at offset " + record.offset(), e);
        }
        if (event.productId() == null || (!event.deleted() && (event.name() == null || event.price() == null))) {
            throw new MalformedEventException("Product event violates the contract at offset " + record.offset());
        }
        return event;
    }
}
