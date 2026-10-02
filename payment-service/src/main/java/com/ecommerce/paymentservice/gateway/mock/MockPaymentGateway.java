package com.ecommerce.paymentservice.gateway.mock;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ecommerce.paymentservice.domain.PaymentMethod;
import com.ecommerce.paymentservice.domain.PaymentStatus;
import com.ecommerce.paymentservice.dto.ChargeRequest;
import com.ecommerce.paymentservice.dto.PaymentResult;
import com.ecommerce.paymentservice.dto.RefundRequest;
import com.ecommerce.paymentservice.exception.IllegalRefundAmountException;
import com.ecommerce.paymentservice.exception.IllegalRefundStateException;
import com.ecommerce.paymentservice.exception.TransactionNotFoundException;
import com.ecommerce.paymentservice.gateway.PaymentGateway;

@Component
@ConditionalOnProperty(name = "payment.gateway", havingValue = "mock", matchIfMissing = true)
public class MockPaymentGateway implements PaymentGateway {

    // store transactions by ID
    private final Map<String, ChargeRecord> transactions = new ConcurrentHashMap<>();
    // charge idempotency: to handle duplicate requests
    private final Map<String, String> chargeIdempotencyIndex = new ConcurrentHashMap<>();
    // refund idempotency: to handle duplicate requests
    private final Map<String, String> refundIdempotencyIndex = new ConcurrentHashMap<>();

    /**
     * Returns the set of payment methods supported by this gateway.
     * 
     * @return A set of payment methods supported by this gateway.
     */
    @Override
    public Set<PaymentMethod> supportedMethods() {
        // for now, we support all payment methods
        return EnumSet.allOf(PaymentMethod.class);
    }

    /**
     * Charges the payment using the mock payment gateway.
     * 
     * @param request The charge request.
     * @return The payment result.
     */
    @Override
    public PaymentResult charge(ChargeRequest request) {
        // Ensures one transaction per idempotency key
        String transactionId = chargeIdempotencyIndex.computeIfAbsent(request.idempotencyKey(),
                k -> "Mock" + UUID.randomUUID());

        // return existing transaction if present, otherwise simulate a new one
        // ensures safe retries
        ChargeRecord exists = transactions.get(transactionId);
        if (exists != null) {
            return convertChargeRecordToResult(exists);
        }

        ChargeRecord result = simulate(request, transactionId);
        // atomically save the result, returning the existing winner if a race
        // condition occurred
        ChargeRecord winner = transactions.putIfAbsent(transactionId, result);
        return convertChargeRecordToResult(winner != null ? winner : result);
    }

    /**
     * Simulates the payment.
     * 
     * @param request       The charge request.
     * @param transactionId The transaction ID.
     * @return The payment result.
     */
    private ChargeRecord simulate(ChargeRequest request, String transactionId) {
        // Check payment token to determine the outcome
        String token = request.paymentToken();

        return switch (token) {
            // Test fail when insufficient funds
            case "TOKEN_INSUFFICIENT_FUNDS" ->
                failed(transactionId, "Insufficient funds", request);
            // Test fail when declined
            case "TOKEN_DECLINED" ->
                failed(transactionId, "Transaction declined by the bank", request);
            // Test fail when timeout
            case "TOKEN_TIMEOUT" -> {
                simulateDelay(5000);
                yield failed(transactionId, "Payment gateway timeout", request);
            }
            // otherwise -> success
            default -> success(transactionId, request);
        };

    }

    /**
     * Simulates a successful payment.
     * 
     * @param transactionId The transaction ID.
     * @return A successful payment result.
     */
    private ChargeRecord success(String transactionId, ChargeRequest request) {
        return new ChargeRecord(transactionId, request.orderId(), request.amount(), request.currency(),
                PaymentStatus.SUCCESS, null, Instant.now(), null, null);
    }

    /**
     * Simulates a failed payment.
     * 
     * @param transactionId The transaction ID.
     * @param reason        The reason for the failure.
     * @return A failed payment result.
     */
    private ChargeRecord failed(String transactionId, String reason, ChargeRequest request) {
        return new ChargeRecord(transactionId, request.orderId(), request.amount(), request.currency(),
                PaymentStatus.FAILED, reason, Instant.now(), null, null);
    }

    /**
     * Simulates a delay.
     * 
     * @param ms The delay time in milliseconds.
     */
    private void simulateDelay(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // always restore the interrupt flag, do not swallow the error
        }
    }

    /**
     * Converts a ChargeRecord to a PaymentResult.
     * 
     * @param record The ChargeRecord to convert.
     * @return The PaymentResult.
     */
    private PaymentResult convertChargeRecordToResult(ChargeRecord record) {
        return new PaymentResult(
                record.transactionId(),
                record.status(),
                null, // no redirect URL for mock
                record.failureReason(),
                record.processedAt());
    }

    /**
     * Simulates a refund.
     * 
     * @param request The refund request.
     * @return A refunded payment result.
     */
    @Override
    public PaymentResult refund(RefundRequest request) {

        // Ensures one refundId per idempotency key
        String refundId = refundIdempotencyIndex.computeIfAbsent(request.idempotencyKey(),
                k -> "Refund" + UUID.randomUUID());

        // Get the original transaction
        ChargeRecord original = transactions.get(request.transactionId());
        // Throw exception if transaction not found
        if (original == null) {
            throw new TransactionNotFoundException(request.transactionId());
        }

        // Throw exception if transaction is not in a state that allows refunds
        if (original.status() != PaymentStatus.SUCCESS && original.status() != PaymentStatus.REFUNDED) {
            throw new IllegalRefundStateException(request.transactionId());
        }

        // check amount to be refunded
        // only support full refund not partial refund
        if (request.amount().compareTo(original.amount()) != 0) {
            throw new IllegalRefundAmountException(request.transactionId());
        }

        // Use compute to atomically update the transaction if it is in a state
        // that allows refunds
        ChargeRecord updated = transactions.compute(request.transactionId(), (id, current) -> {
            // Only refund if the transaction is in a SUCCESS state
            if (current.status() == PaymentStatus.SUCCESS) {
                // Create a new record for the refunded transaction to maintain immutability
                return new ChargeRecord(request.transactionId(), current.orderId(), current.amount(),
                        current.currency(),
                        PaymentStatus.REFUNDED, null, current.processedAt(), refundId, Instant.now());
            }
            // if this is a retry of the same refund -> no-op
            if (current.status() == PaymentStatus.REFUNDED && refundId.equals(current.refundId())) {
                return current;
            }
            // if this is a retry of another refund or failed/declined/null -> error
            throw new IllegalRefundStateException(id);
        });

        return new PaymentResult(updated.refundId(), PaymentStatus.REFUNDED, null, null, updated.refundedAt());
    }

    /**
     * Gets the transaction details, only charge transaction IDs are supported,not
     * refund transaction IDs
     * 
     * @param transactionId The transaction ID.
     * @return The transaction details.
     */
    @Override
    public PaymentResult getTransaction(String transactionId) {
        ChargeRecord transaction = transactions.get(transactionId);
        if (transaction == null) {
            throw new TransactionNotFoundException(transactionId);
        }
        return convertChargeRecordToResult(transaction);
    }

    /**
     * Represents a charge record.
     */
    public static record ChargeRecord(
            String transactionId,
            Long orderId,
            BigDecimal amount,
            String currency,
            PaymentStatus status,
            String failureReason,
            Instant processedAt,
            String refundId,
            Instant refundedAt) {
    }

}
