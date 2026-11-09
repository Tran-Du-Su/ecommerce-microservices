package com.ecommerce.inventoryservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ecommerce.inventoryservice.dto.StockCheckItem;
import com.ecommerce.inventoryservice.entity.Inventory;
import com.ecommerce.inventoryservice.exception.OutOfStockException;
import com.ecommerce.inventoryservice.repository.InventoryRepository;
import com.ecommerce.inventoryservice.repository.StockReservationRepository;

/**
 * Saga steps of inventory-service against a real database: every repeat, sequential or
 * concurrent, must leave the stock as if the call had been made once.
 */
@DataJpaTest
@Import(InventoryService.class)
// Each service call commits for real, as in production; concurrent threads must see each other's commits
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StockReservationTest {

    private static final Long ORDER_ID = 42L;
    private static final Long PRODUCT_ID = 1L;
    private static final Long OTHER_PRODUCT_ID = 2L;
    private static final int THREADS = 10;

    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private InventoryRepository inventoryRepository;
    @Autowired
    private StockReservationRepository reservationRepository;

    @BeforeEach
    void setUp() {
        inventoryRepository.save(Inventory.builder().productId(PRODUCT_ID).quantity(10L).build());
        inventoryRepository.save(Inventory.builder().productId(OTHER_PRODUCT_ID).quantity(1L).build());
    }

    @AfterEach
    void tearDown() {
        reservationRepository.deleteAll();
        inventoryRepository.deleteAll();
    }

    @Test
    void release_calledTwice_shouldReturnStockOnce() {
        inventoryService.reserve(ORDER_ID, List.of(new StockCheckItem(PRODUCT_ID, 3L)));

        inventoryService.release(ORDER_ID);
        inventoryService.release(ORDER_ID);

        assertThat(stock(PRODUCT_ID)).isEqualTo(10L);
    }

    @Test
    void reserve_calledTwice_shouldTakeStockOnce() {
        inventoryService.reserve(ORDER_ID, List.of(new StockCheckItem(PRODUCT_ID, 3L)));
        inventoryService.reserve(ORDER_ID, List.of(new StockCheckItem(PRODUCT_ID, 3L)));

        assertThat(stock(PRODUCT_ID)).isEqualTo(7L);
    }

    @Test
    void release_shouldBeNoOp_whenOrderWasNeverReserved() {
        inventoryService.release(ORDER_ID);

        assertThat(stock(PRODUCT_ID)).isEqualTo(10L);
    }

    @Test
    void reserve_shouldTakeNothing_whenOneProductIsShort() {
        List<StockCheckItem> items = List.of(new StockCheckItem(PRODUCT_ID, 3L),
                new StockCheckItem(OTHER_PRODUCT_ID, 5L));

        assertThatThrownBy(() -> inventoryService.reserve(ORDER_ID, items))
                .isInstanceOf(OutOfStockException.class);

        assertThat(stock(PRODUCT_ID)).isEqualTo(10L);
        assertThat(reservationRepository.existsByOrderId(ORDER_ID)).isFalse();
    }

    @Test
    void release_calledConcurrently_shouldReturnStockOnce() throws Exception {
        inventoryService.reserve(ORDER_ID, List.of(new StockCheckItem(PRODUCT_ID, 3L)));

        runConcurrently(() -> inventoryService.release(ORDER_ID));

        assertThat(stock(PRODUCT_ID)).isEqualTo(10L);
    }

    @Test
    void reserve_calledConcurrently_shouldTakeStockOnce_withoutFailingAnyCall() throws Exception {
        runConcurrently(() -> inventoryService.reserve(ORDER_ID, List.of(new StockCheckItem(PRODUCT_ID, 3L))));

        assertThat(stock(PRODUCT_ID)).isEqualTo(7L);
    }

    private long stock(Long productId) {
        return inventoryRepository.findByProductId(productId).orElseThrow().getQuantity();
    }

    /** Starts the task on {@link #THREADS} threads at once; any exception in a thread fails the test. */
    private void runConcurrently(Runnable task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(executor.submit(() -> {
                startGate.await();
                task.run();
                return null;
            }));
        }
        startGate.countDown();
        try {
            for (Future<?> future : futures) {
                future.get(); // rethrows a thread's exception
            }
        } finally {
            executor.shutdown();
        }
    }
}
