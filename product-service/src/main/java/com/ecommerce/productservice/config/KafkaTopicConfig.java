package com.ecommerce.productservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the product events topic in code, so it always exists with the right settings
 * (the broker has topic auto-creation disabled).
 */
@Configuration
public class KafkaTopicConfig {

    /**
     * Compacted topic: the broker eventually keeps only the latest event per product, so a consumer
     * reading from the beginning rebuilds the full catalogue without replaying every price change.
     */
    @Bean
    public NewTopic productEventsTopic(OutboxRelayProperties properties) {
        return TopicBuilder.name(properties.topic())
                .partitions(properties.partitions())
                .replicas(properties.replicas())
                .compact()
                .build();
    }
}
