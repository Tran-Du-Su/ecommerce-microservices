package com.ecommerce.orderservice.client;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.ecommerce.orderservice.dto.StockCheckRequest;
import com.ecommerce.orderservice.dto.StockShortage;
import com.ecommerce.orderservice.exception.ServiceUnavailableException;

import feign.FeignException;
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
     * Saga step: reserves the order's stock, all or nothing.
     *
     * <p>Retried on timeout and 5xx: inventory keys the reservation by {@code orderId}, so a retry
     * of an attempt that was in fact applied finds the reservation and takes nothing more.
     * Retrying a state-changing call is only safe when the receiver is idempotent.
     *
     * <p>Failure contract for callers:
     * <ul>
     * <li>{@link FeignException.FeignClientException} (4xx, e.g. 409 out of stock): a definite
     * failure, nothing was reserved. Not retried, rethrown unchanged.</li>
     * <li>{@link ServiceUnavailableException}: no answer after all attempts. The stock may or may
     * not be reserved.</li>
     * </ul>
     *
     * <p>The fallbacks sit on {@code @Retry}, the outermost aspect
     * ({@code Retry(CircuitBreaker(TimeLimiter(call)))}). Retry decides by exception type, so the
     * exception must reach it unchanged: a fallback on the inner CircuitBreaker would rewrap
     * {@code CallNotPermittedException} as {@link ServiceUnavailableException}, and Retry would
     * keep retrying an open circuit.
     *
     * @param orderId the saved order, used by inventory as the idempotency key; every attempt
     *                reuses it because Resilience4j re-invokes the method with the same arguments
     * @param request products and quantities to reserve
     * @return CompletableFuture with no result (void)
     */
    @Retry(name = "inventory-service", fallbackMethod = "reserveStockFallback")
    @CircuitBreaker(name = "inventory-service")
    @TimeLimiter(name = "inventory-service")
    public CompletableFuture<Void> reserveStock(Long orderId, StockCheckRequest request) {
        return CompletableFuture.runAsync(() -> inventoryClient.reserve(orderId, request), executor);
    }

    /**
     * Fallback for deterministic rejections (4xx). Resilience4j picks the fallback whose exception
     * parameter is the most specific match, so 4xx land here instead of in the generic fallback.
     * The original exception is kept so its HTTP status and message reach the caller.
     */
    private CompletableFuture<Void> reserveStockFallback(Long orderId, StockCheckRequest request,
            FeignException.FeignClientException exception) {
        return CompletableFuture.failedFuture(exception);
    }

    /** Fallback for "no answer" failures after retries are exhausted or the circuit is open. */
    private CompletableFuture<Void> reserveStockFallback(Long orderId, StockCheckRequest request,
            Throwable exception) {
        return CompletableFuture.failedFuture(new ServiceUnavailableException("inventory-service", exception));
    }

    /**
     * Saga compensation: returns what {@link #reserveStock} took for the order.
     *
     * <p>Only the order id is sent: inventory returns what it recorded, so the caller cannot
     * return a wrong quantity. Retried like {@link #reserveStock}: safe to repeat, and a no-op for
     * an order that reserved nothing. Same failure contract.
     *
     * @param orderId the order whose reservation is released
     * @return CompletableFuture with no result (void)
     */
    @Retry(name = "inventory-service", fallbackMethod = "releaseStockFallback")
    @CircuitBreaker(name = "inventory-service")
    @TimeLimiter(name = "inventory-service")
    public CompletableFuture<Void> releaseStock(Long orderId) {
        return CompletableFuture.runAsync(() -> inventoryClient.release(orderId), executor);
    }

    /** 4xx: rethrown unchanged, like {@link #reserveStockFallback(Long, StockCheckRequest, FeignException.FeignClientException)}. */
    private CompletableFuture<Void> releaseStockFallback(Long orderId, FeignException.FeignClientException exception) {
        return CompletableFuture.failedFuture(exception);
    }

    /** No answer after all attempts: the stock may or may not have been returned. */
    private CompletableFuture<Void> releaseStockFallback(Long orderId, Throwable exception) {
        return CompletableFuture.failedFuture(new ServiceUnavailableException("inventory-service", exception));
    }

}
