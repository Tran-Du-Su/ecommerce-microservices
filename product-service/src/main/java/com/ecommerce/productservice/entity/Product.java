package com.ecommerce.productservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder // Create an object in a simple way without using `new` and `set`.
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private BigDecimal price;

    /**
     * Optimistic lock and event version in one: incremented by Hibernate on every update, so
     * consumers of {@code ProductChangedEvent} can discard stale or duplicate events.
     */
    @Version
    @Setter(AccessLevel.NONE)
    @Column(nullable = false)
    private Long version;
}
