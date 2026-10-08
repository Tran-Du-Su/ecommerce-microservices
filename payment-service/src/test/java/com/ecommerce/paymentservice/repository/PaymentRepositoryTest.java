package com.ecommerce.paymentservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import com.ecommerce.paymentservice.domain.PaymentMethod;
import com.ecommerce.paymentservice.domain.PaymentStatus;
import com.ecommerce.paymentservice.entity.Payment;

@DataJpaTest
public class PaymentRepositoryTest {

        @Autowired
        private PaymentRepository paymentRepository;

        /**
         * Create a payment with a same orderId
         * Save it and assert that the payment is not saved
         * (DataIntegrityViolationException)
         */
        @Test
        void createPending_shouldFail_whenOrderAlreadyHasActivePayment() {
                // Payment already exists with orderId:1, so this should fail
                Payment payment1 = Payment.createPending("idempotencyKey", 1L, PaymentMethod.STRIPE,
                                BigDecimal.valueOf(100),
                                "USD", Instant.now());
                paymentRepository.save(payment1);

                // Payment with same orderId:1, but different idempotency key, so this should
                // fail
                Payment payment2 = Payment.createPending("idempotencyKey1", 1L, PaymentMethod.STRIPE,
                                BigDecimal.valueOf(100),
                                "USD", Instant.now());
                assertThatThrownBy(() -> paymentRepository.saveAndFlush(payment2))
                                .isInstanceOf(DataIntegrityViolationException.class)
                                .satisfies(ex -> assertThat(
                                                ((ConstraintViolationException) ex.getCause()).getConstraintName())
                                                .containsIgnoringCase("uk_payment_active_order_id"));
        }

        /**
         * Create a payment with status PENDING
         * After saving, update the payment status to FAILED
         * Save it and assert that the payment is saved
         * 
         * Create a new Payment with different idempotency key
         * Save it and assert that the payment is saved
         */
        @Test
        void createPaymentPending_shouldSuccess_whenPaymentBeforeIsFailed() {
                Payment paymentPending = Payment.createPending("idempotencyKey", 1L, PaymentMethod.STRIPE,
                                BigDecimal.valueOf(100),
                                "USD", Instant.now());
                paymentRepository.saveAndFlush(paymentPending);
                paymentPending.markFailed("transactionId", "Failed", Instant.now());
                paymentRepository.saveAndFlush(paymentPending);

                Payment paymentPendingNew = Payment.createPending("idempotencyKey1", 1L, PaymentMethod.STRIPE,
                                BigDecimal.valueOf(100),
                                "USD", Instant.now());
                paymentRepository.saveAndFlush(paymentPendingNew);

                // Assert that the payment is saved
                assertThat(paymentPendingNew.getId()).isNotNull();
                assertThat(paymentPendingNew.getStatus()).isEqualTo(PaymentStatus.PENDING);
        }

        /**
         * Create a payment
         * Save it and assert that the payment is saved
         * 
         * Create a new Payment with same idempotency key
         * Save it and assert that the payment is not saved
         * (DataIntegrityViolationException)
         */
        @Test
        void createPayment_shouldFail_whenSameIdempotencyKey() {
                Payment payment = Payment.createPending("idempotencyKey", 1L, PaymentMethod.STRIPE,
                                BigDecimal.valueOf(100),
                                "USD", Instant.now());
                paymentRepository.save(payment);

                Payment payment2 = Payment.createPending("idempotencyKey", 2L, PaymentMethod.STRIPE,
                                BigDecimal.valueOf(100),
                                "USD", Instant.now());
                assertThatThrownBy(() -> paymentRepository.saveAndFlush(payment2))
                                .isInstanceOf(DataIntegrityViolationException.class)
                                .satisfies(ex -> assertThat(
                                                ((ConstraintViolationException) ex.getCause()).getConstraintName())
                                                .containsIgnoringCase("uk_payment_idempotency_key"));
        }

        /**
         * Create a payment and set it to REFUNDED state
         * Save it and assert that the payment is saved
         * 
         * Create a new Payment with different idempotency key
         * Save it and assert that the payment is not saved
         * (DataIntegrityViolationException)
         */
        @Test
        void createPaymentPending_shouldFail_whenPaymentBeforeIsRefunded() {
                Payment payment1 = Payment.createPending("idempotencyKey", 1L, PaymentMethod.STRIPE,
                                BigDecimal.valueOf(100),
                                "USD", Instant.now());
                payment1.markSuccess("transactionId", Instant.now());
                payment1.markRefunded("refundId", Instant.now());
                paymentRepository.save(payment1);

                Payment payment2 = Payment.createPending("idempotencyKey1", 1L, PaymentMethod.STRIPE,
                                BigDecimal.valueOf(100),
                                "USD", Instant.now());
                assertThatThrownBy(() -> paymentRepository.saveAndFlush(payment2))
                                .isInstanceOf(DataIntegrityViolationException.class)
                                .satisfies(ex -> assertThat(
                                                ((ConstraintViolationException) ex.getCause()).getConstraintName())
                                                .containsIgnoringCase("uk_payment_active_order_id"));
        }
}
