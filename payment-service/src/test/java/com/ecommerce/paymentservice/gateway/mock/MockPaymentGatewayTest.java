package com.ecommerce.paymentservice.gateway.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ecommerce.paymentservice.domain.PaymentStatus;
import com.ecommerce.paymentservice.dto.ChargeRequest;
import com.ecommerce.paymentservice.dto.PaymentResult;
import com.ecommerce.paymentservice.dto.RefundRequest;
import com.ecommerce.paymentservice.exception.IllegalRefundAmountException;
import com.ecommerce.paymentservice.exception.IllegalRefundStateException;
import com.ecommerce.paymentservice.exception.TransactionNotFoundException;

public class MockPaymentGatewayTest {

    private MockPaymentGateway mockPaymentGateway;

    @BeforeEach
    void initial() {
        mockPaymentGateway = new MockPaymentGateway();
    }

    @Nested
    class ChargeTest {
        @Test
        void charge_shouldSuccess_whenTokenIsNormal() {
            // Arrange
            ChargeRequest request = createChargeRequest("TOKEN_OK", "ck-1");

            // Act
            PaymentResult result = mockPaymentGateway.charge(request);

            // Assert
            assertThat(result.status()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(result.transactionId()).startsWith("Mock");
            assertThat(result.failureReason()).isNull();

        }

        @Test
        void charge_shouldFail_whenTokenIsDeclined() {
            // Arrange
            ChargeRequest request = createChargeRequest("TOKEN_DECLINED", "ck-1");

            // Act
            PaymentResult result = mockPaymentGateway.charge(request);

            // Assert
            assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(result.failureReason()).isEqualTo("Transaction declined by the bank");

        }

        @Test
        void charge_shouldFail_whenTokenIsInsufficientFunds() {
            // Arrange
            ChargeRequest request = createChargeRequest("TOKEN_INSUFFICIENT_FUNDS", "ck-2");

            // Act
            PaymentResult result = mockPaymentGateway.charge(request);

            // Assert
            assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(result.failureReason()).isEqualTo("Insufficient funds");

        }

        @Test
        void charge_shouldReturnSameTransactionId_whenCallTwiceWithSameIdempotencyKey() {
            // Arrange
            ChargeRequest request1 = createChargeRequest("TOKEN_OK", "ck-3");
            ChargeRequest request2 = createChargeRequest("TOKEN_OK", "ck-3");

            // Act
            PaymentResult result1 = mockPaymentGateway.charge(request1);
            PaymentResult result2 = mockPaymentGateway.charge(request2);

            // Assert
            assertThat(result1.transactionId()).isEqualTo(result2.transactionId());
            assertThat(result1.processedAt()).isEqualTo(result2.processedAt());

        }

        @Test
        void charge_shouldReturnDifferentTransactionId_whenCallTwiceWithDifferentIdempotencyKey() {
            // Arrange
            ChargeRequest request1 = createChargeRequest("TOKEN_OK", "ck-1");
            ChargeRequest request2 = createChargeRequest("TOKEN_OK", "ck-2");

            // Act
            PaymentResult result1 = mockPaymentGateway.charge(request1);
            PaymentResult result2 = mockPaymentGateway.charge(request2);

            // Assert
            assertThat(result1.transactionId()).isNotEqualTo(result2.transactionId());

        }
    }

    @Nested
    class RefundTest {
        @Test
        void refund_shouldSuccess_whenTransactionIsSuccess() {
            // Arrange
            PaymentResult transaction = chargeSuccessfully("TOKEN_OK");
            RefundRequest refundRequest = createRefundRequest(transaction.transactionId(), "rk-1",
                    new BigDecimal("100000"));

            // Act
            PaymentResult result = mockPaymentGateway.refund(refundRequest);

            // Assert
            assertThat(result.status()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(result.transactionId()).startsWith("Refund");
            assertThat(result.transactionId()).isNotEqualTo(refundRequest.transactionId());

        }

        @Test
        void refund_shouldReturnSameTransactionId_whenRefunded() {
            // Arrange
            PaymentResult transaction = chargeSuccessfully("TOKEN_OK");
            RefundRequest refundRequest = createRefundRequest(transaction.transactionId(), "rk-1",
                    new BigDecimal("100000"));

            // Act
            mockPaymentGateway.refund(refundRequest);
            PaymentResult transactionAfterRefund = mockPaymentGateway.getTransaction(transaction.transactionId());

            // Assert
            assertThat(transactionAfterRefund.status()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(transactionAfterRefund.transactionId()).isEqualTo(transaction.transactionId());

        }

        @Test
        void refund_shouldReturnSameTransactionId_whenCallTwiceWithSameIdempotencyKey() {
            // Arrange
            PaymentResult transaction = chargeSuccessfully("TOKEN_OK");
            RefundRequest refundRequest = createRefundRequest(transaction.transactionId(), "rk-1",
                    new BigDecimal("100000"));

            // Act
            PaymentResult result1 = mockPaymentGateway.refund(refundRequest);
            PaymentResult result2 = mockPaymentGateway.refund(refundRequest);

            // Assert
            assertThat(result1.transactionId()).isEqualTo(result2.transactionId());
            assertThat(result1.processedAt()).isEqualTo(result2.processedAt());

        }

        @Test
        void refund_shouldThrowException_whenAlreadyRefunded() {
            // Arrange
            PaymentResult transaction = chargeSuccessfully("TOKEN_OK");
            RefundRequest refundRequest = createRefundRequest(transaction.transactionId(), "rk-1",
                    new BigDecimal("100000"));

            // Act
            mockPaymentGateway.refund(refundRequest);

            RefundRequest refundRequest2 = createRefundRequest(transaction.transactionId(), "rk-2",
                    new BigDecimal("100000"));

            // Assert
            assertThatThrownBy(() -> mockPaymentGateway.refund(refundRequest2))
                    .isInstanceOf(IllegalRefundStateException.class);

        }

        @Test
        void refund_shouldThrowException_whenRefundWithFailedTransaction() {
            // Arrange
            PaymentResult transaction = chargeSuccessfully("TOKEN_DECLINED");
            RefundRequest refundRequest = createRefundRequest(transaction.transactionId(), "rk-1",
                    new BigDecimal("100000"));

            // Assert
            assertThatThrownBy(() -> mockPaymentGateway.refund(refundRequest))
                    .isInstanceOf(IllegalRefundStateException.class);

        }

        @Test
        void refund_shouldThrowException_whenRefundWithNotExistsTransaction() {
            // Arrange
            RefundRequest refundRequest = createRefundRequest("transactionId-not-exists", "rk-1",
                    new BigDecimal("100000"));

            // Assert
            assertThatThrownBy(() -> mockPaymentGateway.refund(refundRequest))
                    .isInstanceOf(TransactionNotFoundException.class);

        }

        @Test
        void refund_shouldThrowException_whenRefundWithInvalidAmount() {
            // Arrange
            PaymentResult transaction = chargeSuccessfully("TOKEN_OK");
            RefundRequest refundRequest = createRefundRequest(transaction.transactionId(), "rk-1",
                    new BigDecimal("200000"));

            // Assert
            assertThatThrownBy(() -> mockPaymentGateway.refund(refundRequest))
                    .isInstanceOf(IllegalRefundAmountException.class);

        }

        @Test
        void refund_shouldSuccess_whenRefundWithDiffAmountScale() {
            // Arrange
            PaymentResult transaction = chargeSuccessfully("TOKEN_OK");
            RefundRequest refundRequest = createRefundRequest(transaction.transactionId(), "rk-1",
                    new BigDecimal("100000.00"));

            // Act
            PaymentResult result = mockPaymentGateway.refund(refundRequest);

            // Assert
            assertThat(result.status()).isEqualTo(PaymentStatus.REFUNDED);

        }

        @Test
        void refund_shouldSuccess_whenRefundIdSameChargeIdempotencyKey() {
            // Arrange
            PaymentResult transaction = chargeSuccessfully("TOKEN_OK");
            RefundRequest refundRequest = createRefundRequest(transaction.transactionId(), "ck-1",
                    new BigDecimal("100000.00"));

            // Act
            PaymentResult result = mockPaymentGateway.refund(refundRequest);

            // Assert
            assertThat(result.status()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(result.transactionId()).isNotEqualTo(refundRequest.transactionId());

        }
    }

    @Nested
    class GetTransactionTest {
        @Test
        void getTransaction_shouldReturnTransaction_whenTransactionExists() {
            // Arrange
            PaymentResult transaction = chargeSuccessfully("TOKEN_OK");

            // Act
            PaymentResult result = mockPaymentGateway.getTransaction(transaction.transactionId());

            // Assert
            assertThat(result.transactionId()).isEqualTo(transaction.transactionId());

        }

        @Test
        void getTransaction_shouldThrowException_whenTransactionNotExists() {
            // Arrange
            assertThatThrownBy(() -> mockPaymentGateway.getTransaction("transactionId-not-exists"))
                    .isInstanceOf(TransactionNotFoundException.class);

        }

        @Test
        void getTransaction_shouldThrowException_whenGetByRefundIdNotExists() {
            // Arrange
            PaymentResult transaction = chargeSuccessfully("TOKEN_OK");
            RefundRequest refundRequest = createRefundRequest(transaction.transactionId(), "rk-1",
                    new BigDecimal("100000.00"));

            // Act
            PaymentResult result = mockPaymentGateway.refund(refundRequest);

            // Assert
            assertThatThrownBy(() -> mockPaymentGateway.getTransaction(result.transactionId()))
                    .isInstanceOf(TransactionNotFoundException.class);

        }

    }

    @Nested
    class ConcurrencyTest {

        private static final int CONCURRENT_REQUEST = 30;
        private PaymentResult transaction;

        @BeforeEach
        void chargeOnce() {
            transaction = chargeSuccessfully("TOKEN_OK");
        }

        @Test
        void refund_shouldRefundOnlyOnce_whenConcurrentRequests() throws Exception {
            // Arrange
            CountDownLatch startGate = new CountDownLatch(1);

            List<Future<PaymentResult>> futures = new ArrayList<>();
            try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUEST)) {
                for (int i = 0; i < CONCURRENT_REQUEST; i++) {
                    String key = "rk-" + i;
                    futures.add(executor.submit(() -> {
                        startGate.await();
                        return mockPaymentGateway.refund(createRefundRequest(transaction.transactionId(), key,
                                new BigDecimal("100000")));
                    }));
                }
                // release them together, AFTER all are submitted
                startGate.countDown();
            }
            // close() waits for all tasks to finish
            // then loop over futures with future.get() + catch ExecutionException

            // Assert
            int countRefundSucess = 0;
            int countRefundFailed = 0;
            for (Future<PaymentResult> future : futures) {
                try {
                    PaymentResult result = future.get();
                    if (result.status() == PaymentStatus.REFUNDED) {
                        countRefundSucess++;
                    }
                } catch (ExecutionException e) {
                    // Catch ExecutionException because future.get() wraps the exception thrown
                    // inside the Callable. If it is not an instance of IllegalRefundStateException,
                    // let the test fail loudly.
                    if (e.getCause() instanceof IllegalRefundStateException) {
                        countRefundFailed++;
                    } else {
                        throw e; // unexpected error -> let the test fail loudly
                    }
                }
            }

            assertThat(countRefundSucess).isEqualTo(1);
            assertThat(countRefundFailed).isEqualTo(CONCURRENT_REQUEST - 1);
            assertThat(mockPaymentGateway.getTransaction(transaction.transactionId()).status())
                    .isEqualTo(PaymentStatus.REFUNDED);

        }

        @Test
        void refund_shouldSuccess_whenConcurrentRequestsSameRefundId() throws Exception {
            // Arrange
            CountDownLatch startGate = new CountDownLatch(1);

            List<Future<PaymentResult>> futures = new ArrayList<>();
            try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUEST)) {
                for (int i = 0; i < CONCURRENT_REQUEST; i++) {
                    String key = "rk-1";
                    futures.add(executor.submit(() -> {
                        startGate.await();
                        return mockPaymentGateway.refund(createRefundRequest(transaction.transactionId(), key,
                                new BigDecimal("100000")));
                    }));
                }
                // release them together, AFTER all are submitted
                startGate.countDown();
            }
            // close() waits for all tasks to finish
            // then loop over futures with future.get() + catch ExecutionException

            // Assert
            Set<String> refundIds = new HashSet<>();
            for (Future<PaymentResult> future : futures) {
                PaymentResult result = future.get();
                refundIds.add(result.transactionId());
            }

            assertThat(refundIds.size()).isEqualTo(1);
        }
    }

    private ChargeRequest createChargeRequest(String token, String idempotencyKey) {
        return new ChargeRequest(1L, new BigDecimal("100000"), "VND", token, idempotencyKey, null);
    }

    private RefundRequest createRefundRequest(String transactionId, String idempotencyKey, BigDecimal amount) {
        return new RefundRequest(1L, transactionId, amount, idempotencyKey);
    }

    private PaymentResult chargeSuccessfully(String token) {
        ChargeRequest request = createChargeRequest(token, "ck-1");
        PaymentResult result = mockPaymentGateway.charge(request);
        return mockPaymentGateway.getTransaction(result.transactionId());
    }

}
