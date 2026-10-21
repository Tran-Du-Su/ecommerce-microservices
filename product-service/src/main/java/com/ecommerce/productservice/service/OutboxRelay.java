package com.ecommerce.productservice.service;

import com.ecommerce.productservice.config.OutboxRelayProperties;
import com.ecommerce.productservice.entity.OutboxEvent;
import com.ecommerce.productservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes outbox events to Kafka (the "relay" half of the Transactional Outbox pattern).
 *
 * <p>Delivery is <b>at-least-once</b>: an event is marked as published only after the broker
 * acknowledged it, so a crash between the two steps sends it again. Consumers deduplicate by
 * {@code version}.
 *
 * <p>Assumes a single active relay. Two relays polling the same table could write two events of
 * one product in reverse order, and log compaction would then keep the older one.
 *
 * <p>Deliberately not {@code @Transactional}: a transaction around the loop would hold a database
 * connection while waiting on the network, and rolling it back after a crash would un-mark events
 * that were already sent, widening the duplicate window to the whole batch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxRelayProperties properties;

    /**
     * Sends pending events oldest first and marks each one as published once acknowledged.
     * Stops at the first failure; the remaining events are retried on the next run.
     *
     * @return number of events published in this run
     */
    @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay-ms:500}")
    public int relayPendingEvents() {
        List<OutboxEvent> batch = outboxEventRepository
                .findByPublishedAtIsNullOrderByIdAsc(Limit.of(properties.batchSize()));

        int published = 0;
        for (OutboxEvent event : batch) {
            if (!send(event)) {
                // Do NOT skip ahead: if v3 of a product failed and we sent its v4 now, the retried v3
                // would land after v4 in the partition and compaction would keep the stale v3.
                break;
            }
            event.markPublished();
            outboxEventRepository.save(event);
            published++;
        }
        return published;
    }

    private boolean send(OutboxEvent event) {
        try {
            // Key = aggregate id as a String: the partition is chosen from the serialized key bytes,
            // so every producer of these events must use the same key type to keep per-product order.
            kafkaTemplate.send(properties.topic(), event.getAggregateId(), event.getPayload())
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            log.warn("Outbox relay could not publish event id={} (aggregateId={}), will retry on next run: {}",
                    event.getId(), event.getAggregateId(), e.toString());
            return false;
        }
    }
}
