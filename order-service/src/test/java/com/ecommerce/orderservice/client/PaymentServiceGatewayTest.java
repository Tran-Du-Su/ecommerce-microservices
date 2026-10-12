package com.ecommerce.orderservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.ecommerce.orderservice.domain.PaymentStatus;
import com.ecommerce.orderservice.dto.PaymentRequest;
import com.ecommerce.orderservice.dto.PaymentResponse;
import com.ecommerce.orderservice.exception.ServiceUnavailableException;

import feign.FeignException;
import feign.Request;
import feign.Response;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Verifies the resilience behaviour of {@link PaymentServiceGateway} with the real Resilience4j
 * aspects. A plain Mockito test would bypass the annotations, because they only work through the
 * Spring proxy.
 *
 * <p>Resilience4j settings are declared here (short timeout, tiny wait) to keep the test fast and
 * independent of config-repo, which is disabled in tests.
 */
@SpringBootTest(properties = {
        "resilience4j.retry.instances.payment-service.max-attempts=2",
        "resilience4j.retry.instances.payment-service.wait-duration=10ms",
        "resilience4j.retry.instances.payment-service.ignore-exceptions[0]=io.github.resilience4j.circuitbreaker.CallNotPermittedException",
        "resilience4j.retry.instances.payment-service.ignore-exceptions[1]=feign.FeignException$FeignClientException",
        "resilience4j.circuitbreaker.instances.payment-service.minimum-number-of-calls=100",
        "resilience4j.circuitbreaker.instances.payment-service.ignore-exceptions[0]=feign.FeignException$FeignClientException",
        "resilience4j.timelimiter.instances.payment-service.timeout-duration=300ms"
})
class PaymentServiceGatewayTest {

    private static final String KEY = "order-1";

    @Autowired
    private PaymentServiceGateway gateway;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @MockitoBean
    private PaymentClient paymentClient;

    @BeforeEach
    void setUp() {
        // The Spring context (and the circuit breaker state) is shared between tests
        circuitBreakerRegistry.circuitBreaker("payment-service").reset();
        reset(paymentClient);
    }

    @Test
    void pay_shouldReturnResponse_whenPaymentServiceAnswers() {
        when(paymentClient.pay(KEY, request())).thenReturn(response(PaymentStatus.SUCCESS));

        PaymentResponse result = gateway.pay(KEY, request()).join();

        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCESS);
        verify(paymentClient, times(1)).pay(KEY, request());
    }

    @Test
    void pay_shouldPropagate4xxUnchanged_withoutRetry() {
        when(paymentClient.pay(anyString(), any())).thenThrow(feignError(409));

        assertThatThrownBy(() -> gateway.pay(KEY, request()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(FeignException.Conflict.class);

        verify(paymentClient, times(1)).pay(anyString(), any());
    }

    @Test
    void pay_shouldRetryThenThrowServiceUnavailable_when5xx() {
        when(paymentClient.pay(anyString(), any())).thenThrow(feignError(503));

        assertThatThrownBy(() -> gateway.pay(KEY, request()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ServiceUnavailableException.class);

        verify(paymentClient, times(2)).pay(anyString(), any());
    }

    @Test
    void pay_shouldRetryTimeoutWithSameKey_thenThrowServiceUnavailable() {
        when(paymentClient.pay(anyString(), any())).thenAnswer(invocation -> {
            Thread.sleep(1_000);
            return response(PaymentStatus.SUCCESS);
        });

        assertThatThrownBy(() -> gateway.pay(KEY, request()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ServiceUnavailableException.class);

        // Both attempts must carry the same key, otherwise the retry could charge twice
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(paymentClient, times(2)).pay(keys.capture(), any());
        assertThat(keys.getAllValues()).containsOnly(KEY);
    }

    @Test
    void pay_shouldFailFastWithoutCallingPaymentService_whenCircuitOpen() {
        circuitBreakerRegistry.circuitBreaker("payment-service").transitionToOpenState();

        assertThatThrownBy(() -> gateway.pay(KEY, request()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ServiceUnavailableException.class);

        verify(paymentClient, never()).pay(anyString(), any());
    }

    private PaymentRequest request() {
        return new PaymentRequest(1L, new BigDecimal("100000"), "VND", "MOMO", "TOKEN_OK", null);
    }

    private PaymentResponse response(PaymentStatus status) {
        return new PaymentResponse(10L, 1L, new BigDecimal("100000.00"), status, "tx-1", null);
    }

    /** Builds the same exception subclass Feign raises for the given HTTP status. */
    private FeignException feignError(int status) {
        Request feignRequest = Request.create(Request.HttpMethod.POST, "/api/payments", Map.of(), null,
                StandardCharsets.UTF_8, null);
        Response feignResponse = Response.builder()
                .status(status)
                .reason("status " + status)
                .request(feignRequest)
                .headers(Map.of())
                .body("{}", StandardCharsets.UTF_8)
                .build();
        return FeignException.errorStatus("PaymentClient#pay", feignResponse);
    }
}
