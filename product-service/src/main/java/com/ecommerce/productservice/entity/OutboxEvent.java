package com.ecommerce.productservice.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A domain event waiting to be relayed to the message broker (Transactional Outbox pattern).
 *
 * <p>The row is inserted in the <b>same local transaction</b> as the business change, so either
 * both are committed or neither is. A separate relay later publishes rows whose
 * {@code publishedAt} is still {@code null}; publishing is therefore at-least-once and consumers
 * must be idempotent.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for JPA only
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String aggregateType;

    /** Becomes the message key, so all events of one aggregate land in the same partition (ordering). */
    @Column(nullable = false, length = 50)
    private String aggregateId;

    @Column(nullable = false, length = 50)
    private String eventType;

    /** Serialized event body (JSON). Stored as text so the relay never needs to know the event classes. */
    @Lob
    @Column(nullable = false)
    private String payload;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant publishedAt;

    /**
     * Creates an unpublished event. This is the only way to create a new row.
     *
     * @param aggregateType type of the changed aggregate, e.g. {@code Product}
     * @param aggregateId   id of the changed aggregate
     * @param eventType     event name, e.g. {@code ProductChanged}
     * @param payload       serialized event body
     * @return a new, not yet persisted outbox event
     */
    public static OutboxEvent create(String aggregateType, String aggregateId, String eventType, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.payload = payload;
        event.createdAt = Instant.now();
        return event;
    }

    /**
     * Marks the event as acknowledged by the broker so the relay will not send it again.
     */
    public void markPublished() {
        this.publishedAt = Instant.now();
    }
}
