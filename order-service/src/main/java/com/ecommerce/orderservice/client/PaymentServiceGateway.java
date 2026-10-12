package com.ecommerce.orderservice.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.ecommerce.orderservice.dto.PaymentRequest;
import com.ecommerce.orderservice.dto.PaymentResponse;
import com.ecommerce.orderservice.exception.ServiceUnavailableException;

import feign.FeignException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;

/**
 * Resilient entry point to payment-service.
 *
 * <p>Unlike {@code InventoryServiceGateway#decreaseInventory}, this call IS retried: payment-service
 * deduplicates requests by {@code Idempotency-Key}, so sending the same request twice cannot charge
 * twice. Retrying a state-changing call is only safe when the receiver is idempotent.
 *
 * <p>Failure contract for callers:
 * <ul>
 * <li>{@link FeignException.FeignClientException} (4xx): payment-service rejected the request
 * deterministically (invalid data, payment already active). Not retried, rethrown unchanged.</li>
 * <li>{@link ServiceUnavailableException}: no answer (timeout, 5xx, connection refused, circuit open).
 * The charge may or may not have happened, so callers must NOT treat it as a failed payment.</li>
 * </ul>
 */
@Component
public class PaymentServiceGateway {

    private static final String SERVICE = "payment-service";

    private final PaymentClient paymentClient;
    private final Executor executor;

    public PaymentServiceGateway(PaymentClient paymentClient,
            @Qualifier("externalCallExecutor") Executor executor) {
        this.paymentClient = paymentClient;
        this.executor = executor;
    }

    /**
     * Charges an order through payment-service.
     *
     * <p>The fallback sits on {@code @Retry} because Retry is the outermost aspect
     * ({@code Retry(CircuitBreaker(TimeLimiter(call)))}); a fallback on an inner aspect would hide
     * failures from Retry and CircuitBreaker.
     *
     * @param idempotencyKey key of this payment attempt; every retry reuses the same value because
     *                       Resilience4j re-invokes the method with the same arguments
     * @param request        payment details
     * @return future completed with the payment outcome (SUCCESS, FAILED or PENDING)
     */
    @Retry(name = SERVICE, fallbackMethod = "payFallback")
    @CircuitBreaker(name = SERVICE)
    @TimeLimiter(name = SERVICE)
    public CompletableFuture<PaymentResponse> pay(String idempotencyKey, PaymentRequest request) {
        return CompletableFuture.supplyAsync(() -> paymentClient.pay(idempotencyKey, request), executor);
    }

    /**
     * Fallback for deterministic rejections (4xx). Resilience4j picks the fallback whose exception
     * parameter is the most specific match, so 4xx land here instead of in the generic fallback.
     * The original exception is kept so its HTTP status and message reach the caller.
     */
    private CompletableFuture<PaymentResponse> payFallback(String idempotencyKey, PaymentRequest request,
            FeignException.FeignClientException exception) {
        return CompletableFuture.failedFuture(exception);
    }

    /**
     * Fallback for "no answer" failures after retries are exhausted or the circuit is open.
     */
    private CompletableFuture<PaymentResponse> payFallback(String idempotencyKey, PaymentRequest request,
            Throwable exception) {
        return CompletableFuture.failedFuture(new ServiceUnavailableException(SERVICE, exception));
    }
}
