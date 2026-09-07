package com.ecommerce.orderservice.client;

import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
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
}
