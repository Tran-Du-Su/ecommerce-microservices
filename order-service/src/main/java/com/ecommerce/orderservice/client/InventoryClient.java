package com.ecommerce.orderservice.client;

import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import com.ecommerce.orderservice.dto.StockCheckRequest;
import com.ecommerce.orderservice.dto.StockShortage;

// Use load balancer (Eureka) to find the inventory-service
// name must match the application name in inventory-service
@FeignClient(name = "inventory-service", path = "/api/inventory")
public interface InventoryClient {

        @PostMapping("/check")
        List<StockShortage> checkInventory(StockCheckRequest request);

        @PostMapping("/decrease")
        void decreaseInventory(StockCheckRequest request);

        /** Saga step. Keyed by the order: inventory applies it at most once per order. */
        @PostMapping("/reservations/{orderId}")
        void reserve(@PathVariable("orderId") Long orderId, StockCheckRequest request);

        /** Saga compensation. No body: inventory returns exactly what it reserved for the order. */
        @PostMapping("/reservations/{orderId}/release")
        void release(@PathVariable("orderId") Long orderId);
}
