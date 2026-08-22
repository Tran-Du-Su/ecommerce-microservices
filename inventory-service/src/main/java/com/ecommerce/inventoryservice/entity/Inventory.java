package com.ecommerce.inventoryservice.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
// Unique constraint là chốt chặn cuối cùng: chỉ tầng DB mới chặn được hai request
// tạo tồn kho cho cùng một productId ở cùng thời điểm.
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