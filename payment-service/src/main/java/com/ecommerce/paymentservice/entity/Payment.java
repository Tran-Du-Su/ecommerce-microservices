package com.ecommerce.paymentservice.entity;

import java.math.BigDecimal;
import java.time.Instant;

import com.ecommerce.paymentservice.domain.PaymentMethod;
import com.ecommerce.paymentservice.domain.PaymentStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

@Entity
@Table(name = "payments", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_idempotency_key", columnNames = "idempotencyKey"),
        @UniqueConstraint(name = "uk_payment_active_order_id", columnNames = "activeOrderId")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Prevent creation from outside
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;

    @Column(nullable = false, precision = 19, scale = 2) // 19 digits total, 2 after decimal point
    private BigDecimal amount;

    @Column(nullable = false, length = 3) // ISO 4217 currency code
    private String currency;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    // null when pending
    private String transactionId;

    private String failureReason;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant processedAt;

    private String refundId;

    private Instant refundedAt;

    private Long activeOrderId;

    /**
     * Creates a new pending payment.
     */
    public static Payment createPending(String idempotencyKey, Long orderId, PaymentMethod method,
            BigDecimal amount, String currency, Instant createdAt) {
        Payment payment = new Payment();
        payment.idempotencyKey = idempotencyKey;
        payment.orderId = orderId;
        payment.paymentMethod = method;
        payment.amount = amount;
        payment.currency = currency;
        payment.status = PaymentStatus.PENDING;
        payment.createdAt = createdAt;
        payment.activeOrderId = orderId;
        return payment;
    }

    /**
     * Marks payment as success.
     */
    public void markSuccess(String transactionId, Instant processedAt) {
        if (this.status != PaymentStatus.PENDING) {
            throw new IllegalStateException("Payment must be in state PENDING to be successful");
        }
        this.transactionId = transactionId;
        this.processedAt = processedAt;
        this.status = PaymentStatus.SUCCESS;
        this.activeOrderId = this.orderId;
    }

    /**
     * Marks payment as failed.
     */
    public void markFailed(String failureReason, Instant processedAt) {
        if (this.status != PaymentStatus.PENDING) {
            throw new IllegalStateException("Payment must be in state PENDING to be failed");
        }
        this.failureReason = failureReason;
        this.processedAt = processedAt;
        this.status = PaymentStatus.FAILED;
        this.activeOrderId = null;
    }

    /**
     * Marks payment as refunded.
     */
    public void markRefunded(String refundId, Instant refundedAt) {
        if (this.status != PaymentStatus.SUCCESS) {
            throw new IllegalStateException("Payment must be in state SUCCESS to be refunded");
        }
        this.refundId = refundId;
        this.refundedAt = refundedAt;
        this.status = PaymentStatus.REFUNDED;
        this.activeOrderId = this.orderId;
    }

}
