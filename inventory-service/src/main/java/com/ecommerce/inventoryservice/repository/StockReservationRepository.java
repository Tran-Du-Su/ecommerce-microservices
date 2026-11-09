package com.ecommerce.inventoryservice.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ecommerce.inventoryservice.entity.StockReservation;
import com.ecommerce.inventoryservice.entity.StockReservation.Status;

public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    boolean existsByOrderId(Long orderId);

    /** Sorted by product, the same lock order as the stock decrease, to avoid deadlocks. */
    List<StockReservation> findByOrderIdOrderByProductIdAsc(Long orderId);

    /**
     * ATOMIC status transition (compare-and-swap, same pattern as the stock decrease): the
     * {@code status = :from} clause is the "compare". Of two concurrent releases, only one gets
     * 1 row back; the other waits for the row lock, re-checks the condition and gets 0.
     *
     * @return 1 if this call made the transition, 0 if the row was not in {@code from}
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE StockReservation r
               SET r.status = :to
             WHERE r.id = :id
               AND r.status = :from
            """)
    int transitionStatus(@Param("id") Long id, @Param("from") Status from, @Param("to") Status to);
}
