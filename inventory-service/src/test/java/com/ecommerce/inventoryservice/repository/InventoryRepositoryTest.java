package com.ecommerce.inventoryservice.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.ecommerce.inventoryservice.entity.Inventory;

@DataJpaTest
public class InventoryRepositoryTest {

    @Autowired
    private InventoryRepository inventoryRepository;

    /**
     * It runs before each @Test, rather than once for the entire class.
     * Why it is needed: @DataJpaTest performs a rollback after each test;
     * therefore, without @BeforeEach to recreate the data, the second test
     * would run against an empty database.
     */
    @BeforeEach
    void setUp() {
        Inventory inventory = Inventory.builder()
                .productId(1L)
                .quantity(10L)
                .build();

        inventoryRepository.save(inventory);
    }

    /**
     * Decrement stock by quantity
     * Assert that the number of updated rows is 1
     * Assert that the stock has been decreased by the specified quantity
     */
    @Test
    void decreaseQuantity_shouldSucceed_whenStockIsSufficient() {
        int updateRows = inventoryRepository.decreaseQuantity(1L, 3L);

        assertThat(updateRows).isEqualTo(1);

        Inventory inventory = inventoryRepository.findByProductId(1L).orElseThrow();
        assertThat(inventory.getQuantity()).isEqualTo(7L);
    }

    /**
     * Decrement stock by quantity
     * Assert that the number of updated rows is 0
     * Insufficient stock → deduction prevented (the quantity check remains at 10,
     * not a negative or incorrect value) — this is precisely the line of code that
     * would have caught the issue if someone had accidentally deleted the `WHERE
     * quantity >= :quantity` condition.
     */
    @Test
    void decreaseQuantity_shouldFail_whenStockIsInsufficient() {
        int updateRows = inventoryRepository.decreaseQuantity(1L, 999L);

        assertThat(updateRows).isEqualTo(0);

        Inventory inventory = inventoryRepository.findByProductId(1L).orElseThrow();
        assertThat(inventory.getQuantity()).isEqualTo(10L);
    }

    /**
     * Decrement stock by quantity
     * Assert that the number of updated rows is 0
     * Product does not exist → no rows updated
     */
    @Test
    void decreaseQuantity_shouldReturnZero_whenProductDoesNotExist() {
        int updateRows = inventoryRepository.decreaseQuantity(999L, 1L);

        assertThat(updateRows).isEqualTo(0);
    }
}
