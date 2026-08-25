package com.ecommerce.inventoryservice.service;

import com.ecommerce.inventoryservice.exception.InventoryAlreadyExistsException;
import com.ecommerce.inventoryservice.exception.InventoryNotFoundException;
import com.ecommerce.inventoryservice.exception.OutOfStockException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecommerce.inventoryservice.dto.InventoryRequest;
import com.ecommerce.inventoryservice.dto.InventoryResponse;
import com.ecommerce.inventoryservice.dto.StockCheckItem;
import com.ecommerce.inventoryservice.dto.StockShortage;
import com.ecommerce.inventoryservice.entity.Inventory;
import com.ecommerce.inventoryservice.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    // create inventory
    public InventoryResponse createInventory(InventoryRequest request) {
        // Chặn từ tầng nghiệp vụ để trả 409 tử tế; unique constraint dưới DB là chốt
        // chặn
        // cuối cùng cho trường hợp hai request tạo cùng lúc.
        if (inventoryRepository.existsByProductId(request.productId())) {
            throw new InventoryAlreadyExistsException(request.productId());
        }

        Inventory inventory = Inventory.builder()
                .productId(request.productId())
                .quantity(request.quantity())
                .build();

        Inventory saved = inventoryRepository.save(inventory);

        return toResponse(saved);
    }

    // get inventory by product id
    public InventoryResponse getInventoryByProductId(Long productId) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new InventoryNotFoundException(productId));

        return toResponse(inventory);
    }

    // check Inventory
    public List<StockShortage> checkInventory(List<StockCheckItem> stockCheckItems) {
        // 1. get inventory
        List<Long> productIds = stockCheckItems.stream().map(StockCheckItem::productId)
                .collect(Collectors.toList());
        Map<Long, Inventory> mapInventory = inventoryRepository.findByProductIdIn(productIds)
                .stream()
                .collect(Collectors.toMap(Inventory::getProductId, inventory -> inventory));

        // 2. check quantity >= quantity
        List<Long> quantities = stockCheckItems.stream().map(StockCheckItem::quantity)
                .collect(Collectors.toList());

        List<StockShortage> shortages = new ArrayList<>();

        for (int i = 0; i < productIds.size(); i++) {
            Long productId = productIds.get(i);
            Long quantity = quantities.get(i);

            Inventory inventory = mapInventory.get(productId);
            long available = inventory == null ? 0L : inventory.getQuantity();
            if (available < quantity) {
                shortages.add(new StockShortage(productId, quantity, available));
            }
        }

        return shortages;
    }

    /**
     * Subtract inventory for the entire order.
     *
     * Each item is subtracted using ONE conditional UPDATE statement
     * (compare-and-swap at
     * the DB layer), so there is no lost update like in the previous
     * read-modify-write
     * version.
     *
     * Sort by productId before looping: two orders that conflict with the same set
     * of
     * products will take row locks in the SAME order, avoiding deadlock like
     * A-waiting-for-B /
     * B-waiting-for-A.
     *
     * @Transactional to rollback previous items if the Nth item is out of stock —
     *                inventory is not subtracted halfway.
     */
    @Transactional
    public void decreaseInventory(List<StockCheckItem> stockCheckItems) {
        stockCheckItems.stream()
                .sorted(Comparator.comparing(StockCheckItem::productId))
                .forEach(item -> {
                    int updated = inventoryRepository.decreaseQuantity(item.productId(), item.quantity());

                    // 0 rows affected = insufficient stock (or no inventory record)
                    if (updated == 0) {
                        throw new OutOfStockException(item.productId());
                    }
                });
    }

    // mapping Entity → DTO
    private InventoryResponse toResponse(Inventory inventory) {
        return new InventoryResponse(
                inventory.getId(),
                inventory.getProductId(),
                inventory.getQuantity());
    }
}
