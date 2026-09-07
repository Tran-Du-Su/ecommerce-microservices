package com.ecommerce.orderservice.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import com.ecommerce.orderservice.dto.ProductResponse;

// Use load balancer (Eureka) to find the product-service
// name must match the application name in product-service
@FeignClient(name = "product-service", path = "/api/products")
public interface ProductClient {

    @GetMapping("/{id}")
    public ProductResponse getProductById(@PathVariable Long id);

    @GetMapping(params = "ids")
    public List<ProductResponse> getProductsByProductIds(@RequestParam List<Long> ids);

}