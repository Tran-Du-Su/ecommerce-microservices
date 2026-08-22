package com.ecommerce.orderservice.client;

import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import com.ecommerce.orderservice.dto.StockCheckRequest;
import com.ecommerce.orderservice.dto.StockShortage;

@FeignClient(name = "inventory-service", url = "${services.inventory-service.url}", path = "/api/inventory")
public interface InventoryClient {

        @PostMapping("/check")
        List<StockShortage> checkInventory(StockCheckRequest request);

        @PostMapping("/decrease")
        void decreaseInventory(StockCheckRequest request);
}
