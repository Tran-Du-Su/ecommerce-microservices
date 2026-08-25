package com.ecommerce.inventoryservice.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
// Unique constraint is the last resort: only the DB layer can prevent two
// requests from creating inventory for the same productId at the same time.
@Table(name = "inventories", uniqueConstraints = @UniqueConstraint(name = "uk_inventories_product_id", columnNames = "product_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder // Create an object in a simple way without using `new` and `set`.
public class Inventory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Long quantity;
}