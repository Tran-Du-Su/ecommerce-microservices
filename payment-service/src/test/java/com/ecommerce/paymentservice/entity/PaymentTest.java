package com.ecommerce.paymentservice.entity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import com.ecommerce.paymentservice.domain.PaymentMethod;

public class PaymentTest {

        /**
         * Create a payment with PENDING status
         * Try to mark it as REFUNDED
         * Expect an IllegalStateException to be thrown
         */
        @Test
        void markRefunded_shouldFail_whenPaymentIsPending() {
                Payment payment = Payment.createPending("idempotencyKey", 1L, PaymentMethod.STRIPE,
                                BigDecimal.valueOf(100),
                                "USD", Instant.now());

                assertThatThrownBy(() -> payment.markRefunded("refundId", Instant.now()))
                                .isInstanceOf(IllegalStateException.class);
        }

        /**
         * Create a payment with SUCCESS status
         * Try to mark it as Failed
         * Expect an IllegalStateException to be thrown
         */
        @Test
        void markFailed_shouldFail_whenPaymentIsSuccess() {
                Payment payment = Payment.createPending("idempotencyKey", 1L, PaymentMethod.STRIPE,
                                BigDecimal.valueOf(100),
                                "USD", Instant.now());
                payment.markSuccess("transactionId", Instant.now());

                assertThatThrownBy(() -> payment.markFailed("transactionId", "failureReason", Instant.now()))
                                .isInstanceOf(IllegalStateException.class);
        }

}
