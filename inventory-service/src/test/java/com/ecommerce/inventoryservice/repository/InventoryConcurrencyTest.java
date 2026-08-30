package com.ecommerce.inventoryservice.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ecommerce.inventoryservice.entity.Inventory;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class InventoryConcurrencyTest {

    // Test for concurrency control
    // decreaseQuantity method should never over-sell

    // setup mock inventory data
    private static final Long PRODUCT_ID = 1L;
    private static final Long INITIAL_STOCK = 10L;
    private static final int CONCURRENT_REQUEST = 30;

    @Autowired
    private InventoryRepository inventoryRepository;

    // Prepare initial inventory before each test
    @BeforeEach
    void setup() {
        Inventory inventory = Inventory.builder()
                .productId(PRODUCT_ID)
                .quantity(INITIAL_STOCK)
                .build();

        inventoryRepository.save(inventory);
    }

    // Clean up after each test
    @AfterEach
    void tearDown() {
        inventoryRepository.deleteAll();
    }

    // Simulate 30 concurrent requests attempting to decrease stock by 1
    // Assert that total successful deductions equal initial stock (10)
    // Assert that final stock is 0 (no over-selling)
    @Test
    void decreaseQuantity_concurrentRequests_shouldNerverOversell() throws Exception {
        // Create thread pool with 30 threads
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUEST);
        // Create latch to wait for all threads to start
        CountDownLatch startGate = new CountDownLatch(1);

        List<Callable<Integer>> tasks = new ArrayList<>();
        // Submit 30 tasks to the executor
        for (int i = 0; i < CONCURRENT_REQUEST; i++) {
            tasks.add(() -> {
                // Wait for all threads to start
                startGate.await();
                return inventoryRepository.decreaseQuantity(PRODUCT_ID, 1L);
            });
        }

        List<Future<Integer>> futures = new ArrayList<>();
        // Submit all tasks and store the futures
        for (Callable<Integer> task : tasks) {
            futures.add(executor.submit(task));
        }

        // Release the latch to start all tasks
        startGate.countDown();

        // Sum all successful deductions
        int successCount = 0;
        // Wait for all tasks to complete and sum the results
        for (Future<Integer> future : futures) {
            successCount += future.get();
        }

        // Shutdown the executor
        executor.shutdown();

        // Assert that total successful deductions equal initial stock
        assertThat(successCount).isEqualTo(INITIAL_STOCK.intValue());

        // Assert that final stock is 0 (no over-selling)
        Inventory finalStore = inventoryRepository.findByProductId(PRODUCT_ID).orElseThrow();
        assertThat(finalStore.getQuantity()).isZero();
    }

}
