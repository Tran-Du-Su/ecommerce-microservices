package com.ecommerce.orderservice.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import com.ecommerce.orderservice.dto.ProductResponse;

@FeignClient(name = "product-service", url = "${services.product-service.url}", path = "/api/products")
public interface ProductClient {

    @GetMapping("/{id}")
    public ProductResponse getProductById(@PathVariable Long id);

    @GetMapping(params = "ids")
    public List<ProductResponse> getProductsByProductIds(@RequestParam List<Long> ids);

}