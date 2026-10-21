package com.ecommerce.productservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings of the outbox relay and of the topic it publishes to.
 *
 * @param topic       destination topic, part of the public contract with consumers
 * @param partitions  fixed up front: adding partitions later re-routes existing keys and breaks
 *                    per-product ordering
 * @param replicas    1 for the single local broker, 3 in production
 * @param batchSize   maximum events relayed per run
 * @param sendTimeout how long to wait for the broker acknowledgement of one event; kept above the
 *                    producer's {@code delivery.timeout.ms} so the producer gives a definite failure
 *                    instead of us abandoning a send that may still succeed later
 */
@ConfigurationProperties("outbox.relay")
public record OutboxRelayProperties(
        @DefaultValue("product.events") String topic,
        @DefaultValue("3") int partitions,
        @DefaultValue("1") short replicas,
        @DefaultValue("100") int batchSize,
        @DefaultValue("15s") Duration sendTimeout) {
}
