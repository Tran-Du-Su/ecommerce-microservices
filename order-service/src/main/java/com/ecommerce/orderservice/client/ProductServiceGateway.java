package com.ecommerce.orderservice.client;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.ecommerce.orderservice.dto.ProductResponse;
import com.ecommerce.orderservice.exception.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;

@Component
public class ProductServiceGateway {

    private final ProductClient productClient;
    private final Executor executor;

    /**
     * DI using constructor
     * 
     * @param productClient
     * @param executor
     */
    public ProductServiceGateway(ProductClient productClient, @Qualifier("externalCallExecutor") Executor executor) {
        this.productClient = productClient;
        this.executor = executor;
    }

    /**
     * Get products by product ids using Feign client
     * CircuitBreaker: name of the circuit breaker - "product-service"
     * fallbackMethod: method to call when the circuit breaker is open -
     * "getProductsByProductIdsFallback"
     * Retry: name of the retry - "product-service"
     * TimeLimiter: name of the time limiter - "product-service"
     * 
     * @param productIds
     * @return List of ProductResponse
     */
    @CircuitBreaker(name = "product-service")
    @Retry(name = "product-service", fallbackMethod = "getProductsByProductIdsFallback")
    @TimeLimiter(name = "product-service")
    public CompletableFuture<List<ProductResponse>> getProductsByProductIds(List<Long> productIds) {
        // Using custom executor for async execution to decouple from the
        // request-processing thread
        return CompletableFuture.supplyAsync(() -> productClient.getProductsByProductIds(productIds),
                executor);
    }

    /**
     * Fallback method for getProductsByProductIds
     * 
     * @param productIds
     * @param exception
     * @return CompletableFuture with exception
     */
    private CompletableFuture<List<ProductResponse>> getProductsByProductIdsFallback(List<Long> productIds,
            Throwable exception) {
        // Create a failed CompletableFuture with the exception
        CompletableFuture<List<ProductResponse>> failed = new CompletableFuture<>();
        // Complete the future with the exception
        failed.completeExceptionally(new ServiceUnavailableException("product-service", exception));
        return failed;
    }

}
