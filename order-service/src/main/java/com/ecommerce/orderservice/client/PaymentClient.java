package com.ecommerce.orderservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import com.ecommerce.orderservice.dto.PaymentRequest;
import com.ecommerce.orderservice.dto.PaymentResponse;

/**
 * Feign client for payment-service, resolved through Eureka by service name.
 *
 * <p>Do not call this directly from business code: go through {@link PaymentServiceGateway},
 * which adds timeout, retry and circuit breaking.
 */
@FeignClient(name = "payment-service", path = "/api/payments")
public interface PaymentClient {

    /**
     * Charges an order.
     *
     * <p>Any 2xx (201 for SUCCESS/FAILED, 202 for PENDING) is decoded into a {@link PaymentResponse};
     * 4xx/5xx are raised as {@code FeignException}.
     *
     * @param idempotencyKey key identifying one payment attempt; retries must reuse it
     * @param request        payment details
     * @return payment outcome
     */
    @PostMapping
    PaymentResponse pay(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PaymentRequest request);
}
