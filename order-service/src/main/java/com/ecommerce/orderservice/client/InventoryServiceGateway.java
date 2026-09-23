package com.ecommerce.orderservice.client;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.ecommerce.orderservice.dto.StockCheckRequest;
import com.ecommerce.orderservice.dto.StockShortage;
import com.ecommerce.orderservice.exception.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;

@Component
public class InventoryServiceGateway {

    private final InventoryClient inventoryClient;
    private final Executor executor;

    /**
     * DI using constructor
     * 
     * @param inventoryClient
     * @param executor
     */
    public InventoryServiceGateway(InventoryClient inventoryClient,
            @Qualifier("externalCallExecutor") Executor executor) {
        this.inventoryClient = inventoryClient;
        this.executor = executor;
    }

    /**
     * Get stock shortages for a list of products using Feign client
     * CircuitBreaker: name of the circuit breaker - "inventory-service"
     * fallbackMethod: method to call when the circuit breaker is open -
     * "checkInventoryFallback"
     * Retry: name of the retry - "inventory-service"
     * TimeLimiter: name of the time limiter - "inventory-service"
     * 
     * @param request StockCheckRequest containing product IDs and quantities
     * @return List of StockShortage containing products that are out of stock
     */
    @CircuitBreaker(name = "inventory-service")
    @Retry(name = "inventory-service", fallbackMethod = "checkInventoryFallback")
    @TimeLimiter(name = "inventory-service")
    public CompletableFuture<List<StockShortage>> checkInventory(StockCheckRequest request) {
        // Async execution to decouple from the request-processing thread
        return CompletableFuture.supplyAsync(() -> inventoryClient.checkInventory(request), executor);
    }

    /**
     * Fallback method for checkInventory
     * 
     * @param request   StockCheckRequest containing product IDs and quantities
     * @param exception Throwable exception
     * @return CompletableFuture with exception
     */
    private CompletableFuture<List<StockShortage>> checkInventoryFallback(StockCheckRequest request,
            Throwable exception) {
        // Create a failed CompletableFuture with the exception
        CompletableFuture<List<StockShortage>> failed = new CompletableFuture<>();
        // Complete the future with the exception
        failed.completeExceptionally(new ServiceUnavailableException("inventory-service", exception));
        return failed;
    }

    /**
     * Decrease inventory for a list of products using Feign client
     * CircuitBreaker: name of the circuit breaker - "inventory-service"
     * fallbackMethod: method to call when the circuit breaker is open -
     * "decreaseInventoryFallback"
     * TimeLimiter: name of the time limiter - "inventory-service"
     * 
     * @param request StockCheckRequest containing product IDs and quantities
     * @return CompletableFuture with no result (void)
     */
    @CircuitBreaker(name = "inventory-service", fallbackMethod = "decreaseInventoryFallback")
    @TimeLimiter(name = "inventory-service")
    public CompletableFuture<Void> decreaseInventory(StockCheckRequest request) {
        // Async execution to decouple from the request-processing thread
        return CompletableFuture.supplyAsync(() -> {
            inventoryClient.decreaseInventory(request);
            return null;
        }, executor);
    }

    /**
     * Fallback method for decreaseInventory
     * 
     * @param request   StockCheckRequest containing product IDs and quantities
     * @param exception Throwable exception
     * @return CompletableFuture with exception
     */
    private CompletableFuture<Void> decreaseInventoryFallback(StockCheckRequest request, Throwable exception) {
        // Create a failed CompletableFuture with the exception
        CompletableFuture<Void> failed = new CompletableFuture<>();
        // Complete the future with the exception
        failed.completeExceptionally(new ServiceUnavailableException("inventory-service", exception));
        return failed;
    }

}
