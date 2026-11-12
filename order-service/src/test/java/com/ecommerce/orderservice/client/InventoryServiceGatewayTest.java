package com.ecommerce.orderservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.ecommerce.orderservice.dto.StockCheckItem;
import com.ecommerce.orderservice.dto.StockCheckRequest;
import com.ecommerce.orderservice.exception.ServiceUnavailableException;

import feign.FeignException;
import feign.Request;
import feign.Response;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;

/**
 * Verifies the resilience behaviour of the saga calls of {@link InventoryServiceGateway} with the
 * real Resilience4j aspects (they only work through the Spring proxy).
 *
 * <p>The retry settings mirror the {@code inventory-service} instance in config-repo, which is
 * disabled in tests: timeouts ARE retried (reserve and release are idempotent per order), 4xx are
 * not. Only the durations are shortened to keep the test fast.
 */
@SpringBootTest(properties = {
        "resilience4j.retry.instances.inventory-service.max-attempts=3",
        "resilience4j.retry.instances.inventory-service.wait-duration=10ms",
        "resilience4j.retry.instances.inventory-service.ignore-exceptions[0]=io.github.resilience4j.circuitbreaker.CallNotPermittedException",
        "resilience4j.retry.instances.inventory-service.ignore-exceptions[1]=feign.FeignException$FeignClientException",
        "resilience4j.circuitbreaker.instances.inventory-service.minimum-number-of-calls=100",
        "resilience4j.circuitbreaker.instances.inventory-service.ignore-exceptions[0]=feign.FeignException$FeignClientException",
        "resilience4j.timelimiter.instances.inventory-service.timeout-duration=300ms"
})
class InventoryServiceGatewayTest {

    private static final Long ORDER_ID = 501L;

    @Autowired
    private InventoryServiceGateway gateway;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RetryRegistry retryRegistry;

    @MockitoBean
    private InventoryClient inventoryClient;

    @BeforeEach
    void setUp() {
        // The Spring context (and the circuit breaker state) is shared between tests
        circuitBreakerRegistry.circuitBreaker("inventory-service").reset();
        reset(inventoryClient);
    }

    /** The scenario broken by hand: first attempt too slow (but applied), the retry succeeds. */
    @Test
    void reserveStock_shouldRetryTimeoutWithSameOrderId_andSucceed() {
        doAnswer(invocation -> {
            Thread.sleep(1_000); // longer than the 300ms time limit
            return null;
        }).doNothing().when(inventoryClient).reserve(anyLong(), any());

        gateway.reserveStock(ORDER_ID, request()).join();

        // Same order id on both attempts: inventory recognises the second one as a repeat
        ArgumentCaptor<Long> orderIds = ArgumentCaptor.forClass(Long.class);
        verify(inventoryClient, times(2)).reserve(orderIds.capture(), any());
        assertThat(orderIds.getAllValues()).containsOnly(ORDER_ID);
    }

    @Test
    void reserveStock_shouldPropagate409Unchanged_withoutRetry() {
        doThrow(feignError(409)).when(inventoryClient).reserve(anyLong(), any());

        assertThatThrownBy(() -> gateway.reserveStock(ORDER_ID, request()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(FeignException.Conflict.class);

        // Out of stock is a definite answer: asking again cannot change it
        verify(inventoryClient, times(1)).reserve(anyLong(), any());
    }

    @Test
    void reserveStock_shouldRetryThenThrowServiceUnavailable_when5xx() {
        doThrow(feignError(503)).when(inventoryClient).reserve(anyLong(), any());

        assertThatThrownBy(() -> gateway.reserveStock(ORDER_ID, request()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ServiceUnavailableException.class);

        verify(inventoryClient, times(3)).reserve(anyLong(), any());
    }

    /**
     * Guards the fallback position: Retry skips {@code CallNotPermittedException} by type, so a
     * fallback on the inner CircuitBreaker (which rewraps it as ServiceUnavailableException) would
     * make Retry hammer an open circuit.
     */
    @Test
    void reserveStock_shouldFailFastWithoutRetry_whenCircuitOpen() {
        circuitBreakerRegistry.circuitBreaker("inventory-service").transitionToOpenState();
        Retry.Metrics retryMetrics = retryRegistry.retry("inventory-service").getMetrics();
        long withRetryBefore = retryMetrics.getNumberOfFailedCallsWithRetryAttempt();
        long withoutRetryBefore = retryMetrics.getNumberOfFailedCallsWithoutRetryAttempt();

        assertThatThrownBy(() -> gateway.reserveStock(ORDER_ID, request()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ServiceUnavailableException.class);

        verify(inventoryClient, never()).reserve(anyLong(), any());
        assertThat(retryMetrics.getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(withRetryBefore);
        assertThat(retryMetrics.getNumberOfFailedCallsWithoutRetryAttempt()).isEqualTo(withoutRetryBefore + 1);
    }

    @Test
    void releaseStock_shouldRetryTimeoutWithSameOrderId_andSucceed() {
        doAnswer(invocation -> {
            Thread.sleep(1_000);
            return null;
        }).doNothing().when(inventoryClient).release(anyLong());

        gateway.releaseStock(ORDER_ID).join();

        ArgumentCaptor<Long> orderIds = ArgumentCaptor.forClass(Long.class);
        verify(inventoryClient, times(2)).release(orderIds.capture());
        assertThat(orderIds.getAllValues()).containsOnly(ORDER_ID);
    }

    @Test
    void releaseStock_shouldPropagate4xxUnchanged_withoutRetry() {
        doThrow(feignError(404)).when(inventoryClient).release(anyLong());

        assertThatThrownBy(() -> gateway.releaseStock(ORDER_ID).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(FeignException.NotFound.class);

        verify(inventoryClient, times(1)).release(anyLong());
    }

    private StockCheckRequest request() {
        return new StockCheckRequest(List.of(new StockCheckItem(100L, 3L)));
    }

    /** Builds the same exception subclass Feign raises for the given HTTP status. */
    private FeignException feignError(int status) {
        Request feignRequest = Request.create(Request.HttpMethod.POST, "/api/inventory/reservations/" + ORDER_ID,
                Map.of(), null, StandardCharsets.UTF_8, null);
        Response feignResponse = Response.builder()
                .status(status)
                .reason("status " + status)
                .request(feignRequest)
                .headers(Map.of())
                .body("{}", StandardCharsets.UTF_8)
                .build();
        return FeignException.errorStatus("InventoryClient#reserve", feignResponse);
    }
}
