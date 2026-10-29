package com.ecommerce.orderservice.config;

import com.ecommerce.orderservice.exception.MalformedEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Error handling for the product event consumer.
 *
 * <p>Two kinds of failure, two policies:
 * <ul>
 *   <li><b>Poison message</b> ({@link MalformedEventException}): retrying cannot help, so it goes to the
 *       dead-letter topic at once and the partition moves on. Skipping one event is acceptable only
 *       because events carry the full state: the next event of that product repairs the view.</li>
 *   <li><b>Anything else</b> (typically our own database unavailable): retried with capped exponential
 *       back-off and <b>no limit</b>. The only dependency is this service's database, so while it is
 *       down no event of any product can be applied; giving up would just move healthy events to the
 *       dead-letter topic. A blocked partition shows up as consumer lag, which is what to alert on.</li>
 * </ul>
 * Spring Kafka's default (retry 9 times, then log and skip) is deliberately replaced: it would silently
 * drop events on a short database outage.
 */
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public DefaultErrorHandler productEventErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                        ProductEventsProperties properties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(properties.dltTopic(), record.partition()));

        ExponentialBackOff backOff = new ExponentialBackOff(properties.retryInitialInterval().toMillis(), 2.0);
        backOff.setMaxInterval(properties.retryMaxInterval().toMillis());
        backOff.setMaxElapsedTime(Long.MAX_VALUE);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(MalformedEventException.class);
        return handler;
    }

    /**
     * Not compacted: unlike the source topic, every failed record must be kept for inspection and replay.
     */
    @Bean
    public NewTopic productEventsDeadLetterTopic(ProductEventsProperties properties) {
        return TopicBuilder.name(properties.dltTopic())
                .partitions(properties.dltPartitions())
                .replicas(1)
                .build();
    }
}
