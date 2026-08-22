package com.ecommerce.inventoryservice.controller;

import org.springframework.web.bind.annotation.RequestMapping;

import com.ecommerce.inventoryservice.dto.InventoryResponse;
import com.ecommerce.inventoryservice.dto.StockCheckRequest;
import com.ecommerce.inventoryservice.dto.StockShortage;
import com.ecommerce.inventoryservice.dto.InventoryRequest;
import com.ecommerce.inventoryservice.service.InventoryService;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryResponse createInventory(@Valid @RequestBody InventoryRequest request) {
        return inventoryService.createInventory(request);
    }

    @GetMapping("/{productId}")
    public InventoryResponse getInventoryByProductId(@PathVariable Long productId) {
        return inventoryService.getInventoryByProductId(productId);
    }

    @PostMapping("/check")
    public List<StockShortage> checkInventory(@RequestBody @Valid StockCheckRequest request) {
        return inventoryService.checkInventory(request.items());
    }

    @PostMapping("/decrease")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decreaseInventory(@RequestBody @Valid StockCheckRequest request) {
        inventoryService.decreaseInventory(request.items());
    }

}
