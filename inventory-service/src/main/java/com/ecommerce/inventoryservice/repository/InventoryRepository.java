package com.ecommerce.inventoryservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.ecommerce.inventoryservice.entity.Inventory;
import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {
  Optional<Inventory> findByProductId(Long productId);

  List<Inventory> findByProductIdIn(List<Long> productIds);

  boolean existsByProductId(Long productId);

  /**
   * ATOMIC inventory subtraction: reads - compares - updates in ONE UPDATE
   * statement,
   * so there is no gap for another request to slip in (lost update).
   * The `quantity >= :quantity` clause is the "compare" part of compare-and-swap;
   * the number of rows returned is the signal: 1 = subtraction successful,
   * 0 = insufficient stock.
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("""
      UPDATE Inventory i
         SET i.quantity = i.quantity - :quantity
       WHERE i.productId = :productId
         AND i.quantity >= :quantity
      """)
  int decreaseQuantity(@Param("productId") Long productId, @Param("quantity") Long quantity);
}
