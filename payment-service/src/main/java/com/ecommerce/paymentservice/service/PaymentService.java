package com.ecommerce.paymentservice.service;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import com.ecommerce.paymentservice.domain.PaymentMethod;
import com.ecommerce.paymentservice.dto.ChargeRequest;
import com.ecommerce.paymentservice.dto.PaymentRequest;
import com.ecommerce.paymentservice.dto.PaymentResponse;
import com.ecommerce.paymentservice.dto.PaymentResult;
import com.ecommerce.paymentservice.entity.Payment;
import com.ecommerce.paymentservice.exception.PaymentAlreadyActiveException;
import com.ecommerce.paymentservice.exception.UnsupportedPaymentMethodException;
import com.ecommerce.paymentservice.gateway.PaymentGateway;
import com.ecommerce.paymentservice.repository.PaymentRepository;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final Map<PaymentMethod, PaymentGateway> paymentGateways;

    /**
     * Constructs PaymentService.
     * 
     * @param paymentRepository payment repository
     * @param paymentGateways   list of payment gateways
     */
    public PaymentService(PaymentRepository paymentRepository, List<PaymentGateway> paymentGateways) {
        this.paymentRepository = paymentRepository;
        // Create an unmodifiable map of payment gateways
        Map<PaymentMethod, PaymentGateway> map = new EnumMap<>(PaymentMethod.class);
        for (PaymentGateway paymentGateway : paymentGateways) {
            for (PaymentMethod paymentMethod : paymentGateway.supportedMethods()) {
                // Check if payment method is already claimed by another gateway
                PaymentGateway previous = map.putIfAbsent(paymentMethod, paymentGateway);
                // if payment method is already claimed by another gateway, throw an exception
                if (previous != null) {
                    throw new IllegalStateException("Payment method " + paymentMethod + " is claimed by both "
                            + previous.getClass().getSimpleName() + " and "
                            + paymentGateway.getClass().getSimpleName());
                }

            }
        }
        // Make payment gateways unmodifiable to prevent runtime changes
        this.paymentGateways = Collections.unmodifiableMap(map);
    }

    /**
     * Pay for an order.
     * 
     * @param idempotencyKey unique key to prevent duplicate payments
     * @param request        payment request
     * @return payment response
     */
    public PaymentResponse pay(String idempotencyKey, PaymentRequest request) {
        // Check if payment already exists for idempotency key
        Optional<Payment> paymentOptional = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (paymentOptional.isPresent()) {
            return toResponse(paymentOptional.get());
        }

        // Get payment gateway for the payment method
        PaymentGateway paymentGateway = paymentGateways.get(request.paymentMethod());
        if (paymentGateway == null) {
            throw new UnsupportedPaymentMethodException(request.paymentMethod());
        }

        // Save pending payment
        Payment payment = Payment.createPending(idempotencyKey, request.orderId(), request.paymentMethod(),
                request.amount(), request.currency(), Instant.now());
        try {
            paymentRepository.saveAndFlush(payment);
        } catch (DataIntegrityViolationException e) {
            // Handle constraint violation exceptions
            if (e.getCause() instanceof ConstraintViolationException cve
                    && cve.getConstraintName() != null) {
                String constraintName = cve.getConstraintName();
                if (constraintName.toLowerCase().contains("uk_payment_idempotency_key")) {
                    // if idempotency key already exists, it means payment was already processed
                    return toResponse(paymentRepository.findByIdempotencyKey(idempotencyKey).get());
                } else if (constraintName.toLowerCase().contains("uk_payment_active_order_id")) {
                    // if active order id already exists, it means payment is already active
                    throw new PaymentAlreadyActiveException(request.orderId());
                }
            }
            throw e;
        }

        // Create charge request
        ChargeRequest chargeRequest = new ChargeRequest(
                request.orderId(),
                request.amount(),
                request.currency(),
                request.paymentToken(),
                payment.getIdempotencyKey(),
                request.returnUrl());
        // Charge payment through payment gateway
        PaymentResult result;
        try {
            result = paymentGateway.charge(chargeRequest);
        } catch (RuntimeException e) {
            // No answer from the gateway: the charge may or may not have happened, so keep
            // PENDING
            log.warn("Charge outcome unknown for idempotencyKey={}, payment kept PENDING", idempotencyKey, e);
            return toResponse(payment);
        }

        // Update payment status based on result
        switch (result.status()) {
            // Mark payment as success
            case SUCCESS:
                payment.markSuccess(result.transactionId(), result.processedAt());
                break;
            // Mark payment as failed
            case FAILED:
                payment.markFailed(result.transactionId(), result.failureReason(), result.processedAt());
                break;
            case PENDING:
                break;
            default:
                throw new IllegalStateException("Unknown payment status: " + result.status());
        }

        // Save payment
        payment = paymentRepository.saveAndFlush(payment);
        // Return payment response
        return toResponse(payment);
    }

    /**
     * Converts payment to payment response.
     */
    private PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getIdempotencyKey(),
                payment.getOrderId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getPaymentMethod(),
                payment.getStatus(),
                payment.getTransactionId(),
                payment.getFailureReason(),
                payment.getCreatedAt(),
                payment.getProcessedAt());
    }

}
