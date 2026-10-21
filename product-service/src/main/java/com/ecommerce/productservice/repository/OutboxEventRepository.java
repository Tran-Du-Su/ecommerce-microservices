package com.ecommerce.productservice.repository;

import com.ecommerce.productservice.entity.OutboxEvent;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /** Oldest unpublished events first: the relay must send them in the order they were recorded. */
    List<OutboxEvent> findByPublishedAtIsNullOrderByIdAsc(Limit limit);
}
