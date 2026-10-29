package com.ecommerce.orderservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Consumption settings for product-service events.
 *
 * @param topic                topic owned and created by product-service
 * @param dltTopic             dead-letter topic owned by this consumer; named after the consumer so
 *                             each service can inspect and replay its own failures
 * @param dltPartitions        must be at least the source topic's partition count: a failed record
 *                             is published to the same partition number it came from
 * @param retryInitialInterval first wait before retrying a transient failure
 * @param retryMaxInterval     cap of the exponential back-off; kept well under
 *                             {@code max.poll.interval.ms} (5 min) so a long wait is not mistaken
 *                             for a dead consumer
 */
@ConfigurationProperties("product-events")
public record ProductEventsProperties(
        @DefaultValue("product.events") String topic,
        @DefaultValue("product.events.order-service.DLT") String dltTopic,
        @DefaultValue("3") int dltPartitions,
        @DefaultValue("1s") Duration retryInitialInterval,
        @DefaultValue("60s") Duration retryMaxInterval) {
}
