package com.ecommerce.productservice.service;

import com.ecommerce.productservice.config.OutboxRelayProperties;
import com.ecommerce.productservice.entity.OutboxEvent;
import com.ecommerce.productservice.repository.OutboxEventRepository;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final String TOPIC = "product.events";

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = relayWithSendTimeout(Duration.ofSeconds(1));
    }

    @Test
    void publishesPendingEventsInOrder_andMarksEachAsPublished() {
        OutboxEvent first = event("1", "{v0}");
        OutboxEvent second = event("1", "{v1}");
        OutboxEvent third = event("2", "{v0}");
        givenPending(first, second, third);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(acked());

        int published = relay.relayPendingEvents();

        assertThat(published).isEqualTo(3);
        InOrder inOrder = inOrder(kafkaTemplate);
        inOrder.verify(kafkaTemplate).send(TOPIC, "1", "{v0}");
        inOrder.verify(kafkaTemplate).send(TOPIC, "1", "{v1}");
        inOrder.verify(kafkaTemplate).send(TOPIC, "2", "{v0}");
        assertThat(List.of(first, second, third)).allSatisfy(e -> assertThat(e.getPublishedAt()).isNotNull());
        verify(outboxEventRepository, times(3)).save(any());
    }

    @Test
    void stopsAtFirstFailure_soLaterEventsCannotOvertakeIt() {
        OutboxEvent v0 = event("1", "{v0}");
        OutboxEvent v1 = event("1", "{v1}");
        OutboxEvent v2 = event("1", "{v2}");
        givenPending(v0, v1, v2);
        when(kafkaTemplate.send(TOPIC, "1", "{v0}")).thenReturn(acked());
        when(kafkaTemplate.send(TOPIC, "1", "{v1}")).thenReturn(CompletableFuture.failedFuture(new KafkaException("broker down")));

        int published = relay.relayPendingEvents();

        assertThat(published).isEqualTo(1);
        verify(kafkaTemplate, never()).send(TOPIC, "1", "{v2}");
        assertThat(v0.getPublishedAt()).isNotNull();
        assertThat(v1.getPublishedAt()).isNull();
        assertThat(v2.getPublishedAt()).isNull();
        verify(outboxEventRepository, times(1)).save(any());
    }

    @Test
    void whenBrokerDoesNotAcknowledgeInTime_eventStaysUnpublished() {
        relay = relayWithSendTimeout(Duration.ofMillis(50));
        OutboxEvent event = event("1", "{v0}");
        givenPending(event);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());

        int published = relay.relayPendingEvents();

        assertThat(published).isZero();
        assertThat(event.getPublishedAt()).isNull();
        verify(outboxEventRepository, never()).save(any());
    }

    private OutboxRelay relayWithSendTimeout(Duration sendTimeout) {
        return new OutboxRelay(outboxEventRepository, kafkaTemplate,
                new OutboxRelayProperties(TOPIC, 3, (short) 1, 100, sendTimeout));
    }

    private void givenPending(OutboxEvent... events) {
        when(outboxEventRepository.findByPublishedAtIsNullOrderByIdAsc(any())).thenReturn(List.of(events));
    }

    private static OutboxEvent event(String aggregateId, String payload) {
        return OutboxEvent.create("Product", aggregateId, "ProductChanged", payload);
    }

    private static CompletableFuture<SendResult<String, String>> acked() {
        return CompletableFuture.completedFuture(null);
    }
}
