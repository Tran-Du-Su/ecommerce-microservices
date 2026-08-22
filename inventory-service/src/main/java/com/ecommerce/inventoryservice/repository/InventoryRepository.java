package com.ecommerce.inventoryservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ecommerce.inventoryservice.entity.Inventory;
import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {
    Optional<Inventory> findByProductId(Long productId);

    List<Inventory> findByProductIdIn(List<Long> productIds);

    boolean existsByProductId(Long productId);

    /**
     * Trừ tồn kho NGUYÊN TỬ: đọc - so sánh - ghi gộp trong MỘT câu UPDATE,
     * nên không tồn tại khoảng trống để request khác chen vào (lost update).
     * Mệnh đề `quantity >= :quantity` là vế "compare" của compare-and-swap;
     * số dòng trả về là tín hiệu: 1 = trừ thành công, 0 = không đủ hàng.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Inventory i
               SET i.quantity = i.quantity - :quantity
             WHERE i.productId = :productId
               AND i.quantity >= :quantity
            """)
    int decreaseQuantity(@Param("productId") Long productId, @Param("quantity") Long quantity);
}
