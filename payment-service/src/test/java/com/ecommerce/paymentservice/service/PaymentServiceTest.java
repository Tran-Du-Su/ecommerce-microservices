package com.ecommerce.paymentservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import com.ecommerce.paymentservice.domain.PaymentMethod;
import com.ecommerce.paymentservice.domain.PaymentStatus;
import com.ecommerce.paymentservice.dto.ChargeRequest;
import com.ecommerce.paymentservice.dto.PaymentRequest;
import com.ecommerce.paymentservice.dto.PaymentResponse;
import com.ecommerce.paymentservice.dto.PaymentResult;
import com.ecommerce.paymentservice.entity.Payment;
import com.ecommerce.paymentservice.exception.PaymentAlreadyActiveException;
import com.ecommerce.paymentservice.exception.UnsupportedPaymentMethodException;
import com.ecommerce.paymentservice.gateway.PaymentGateway;
import com.ecommerce.paymentservice.repository.PaymentRepository;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String KEY = "order-1-key";

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentGateway gateway;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        // Mock the gateway to return the expected payment method
        when(gateway.supportedMethods()).thenReturn(Set.of(PaymentMethod.MOMO));

        // Create service with a list containing our mock gateway
        paymentService = new PaymentService(paymentRepository, List.of(gateway));
    }

    /**
     * Create a mock payment request for testing
     * 
     * @return PaymentRequest
     */
    private PaymentRequest request(PaymentMethod method) {
        return new PaymentRequest(1L, new BigDecimal("100000"), "VND", method, "TOKEN_OK", null);
    }

    @Test
    @DisplayName("Pay should return existing payment when key already used")
    void pay_shouldReturnExistingPayment_withoutCharging_whenKeyAlreadyUsed() {
        // Arrange
        // Create a mock payment that already exists with a transaction ID and success
        // status
        Payment existing = Payment.createPending(KEY, 1L, PaymentMethod.MOMO,
                new BigDecimal("100000"), "VND", Instant.now());
        existing.markSuccess("tx-old", Instant.now());
        // stub findByIdempotencyKey() to return the existing payment
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));

        // Act
        PaymentResponse response = paymentService.pay(KEY, request(PaymentMethod.MOMO));

        // Assert
        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(response.transactionId()).isEqualTo("tx-old");

        // Verify
        verify(gateway, never()).charge(any());
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Pay should mark success and pass same key to gateway when gateway succeeds")
    void pay_shouldMarkSuccess_andPassSameKeyToGateway_whenGatewaySucceeds() {
        // Arrange
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());

        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(gateway.charge(any())).thenReturn(
                new PaymentResult("tx-123", PaymentStatus.SUCCESS, null, null, Instant.now()));

        // Act
        PaymentResponse response = paymentService.pay(KEY, request(PaymentMethod.MOMO));

        // Assert
        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(response.transactionId()).isEqualTo("tx-123");

        // Verify
        ArgumentCaptor<ChargeRequest> captor = ArgumentCaptor.forClass(ChargeRequest.class);
        verify(gateway).charge(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo(KEY);
        // verify that saveAndFlush() was called twice
        // 1: create pending payment
        // 2: update payment status to success
        verify(paymentRepository, times(2)).saveAndFlush(any());
    }

    @Test
    @DisplayName("Pay should mark failed and pass same key to gateway when gateway fails")
    void pay_shouldMarkFailed_andPassSameKeyToGateway_whenGatewayFails() {
        // Arrange
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(gateway.charge(any())).thenReturn(
                new PaymentResult("tx-123", PaymentStatus.FAILED, null, "Payment failed", Instant.now()));

        // Act
        PaymentResponse response = paymentService.pay(KEY, request(PaymentMethod.MOMO));

        // Assert
        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.transactionId()).isEqualTo("tx-123");
        assertThat(response.failureReason()).isEqualTo("Payment failed");

        // Verify
        ArgumentCaptor<ChargeRequest> captor = ArgumentCaptor.forClass(ChargeRequest.class);
        verify(gateway).charge(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo(KEY);
        // verify that saveAndFlush() was called twice
        // 1: create pending payment
        // 2: update payment status to failed
        verify(paymentRepository, times(2)).saveAndFlush(any());
    }

    @Test
    @DisplayName("Pay should stay pending when gateway returns pending")
    void pay_shouldStayPending_whenGatewayReturnsPending() {
        // Arrange
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());

        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(gateway.charge(any())).thenReturn(
                new PaymentResult(null, PaymentStatus.PENDING, null, null, Instant.now()));

        // Act
        PaymentResponse response = paymentService.pay(KEY, request(PaymentMethod.MOMO));

        // Assert
        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.transactionId()).isNull();

        // Verify
        ArgumentCaptor<ChargeRequest> captor = ArgumentCaptor.forClass(ChargeRequest.class);
        verify(gateway).charge(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo(KEY);
        // verify that saveAndFlush() was called twice
        // 1: create pending payment
        // 2: persist gateway result
        verify(paymentRepository, times(2)).saveAndFlush(any());
    }

    @Test
    @DisplayName("Pay should not call gateway when method not supported")
    void pay_shouldThrowUnsupportedPaymentMethodException_whenMethodNotSupported() {
        // Arrange
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());

        // Act + Assert
        assertThatThrownBy(() -> paymentService.pay(KEY, request(PaymentMethod.STRIPE)))
                .isInstanceOf(UnsupportedPaymentMethodException.class);

        // Verify
        verify(gateway, never()).charge(any());
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Pay should throw PaymentAlreadyActiveException when payment is already active")
    void pay_shouldThrowPaymentAlreadyActiveException_whenPaymentIsAlreadyActive() {
        // Arrange
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());

        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenThrow(createConstraintViolationException(
                        "PUBLIC.UK_PAYMENT_ACTIVE_ORDER_ID INDEX PUBLIC.UK_PAYMENT_ACTIVE_ORDER_ID_INDEX_8"));

        // Act + Assert
        assertThatThrownBy(() -> paymentService.pay(KEY, request(PaymentMethod.MOMO)))
                .isInstanceOf(PaymentAlreadyActiveException.class);

        // Verify
        verify(gateway, never()).charge(any());
        // verify that saveAndFlush() was called once
        // 1: create pending payment
        verify(paymentRepository, times(1)).saveAndFlush(any());
    }

    @Test
    @DisplayName("Pay should throw DataAccessResourceFailureException when database is down")
    void pay_shouldThrowDataAccessResourceFailureException_whenDatabaseDown() {
        // Arrange
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());

        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        // Act + Assert
        assertThatThrownBy(() -> paymentService.pay(KEY, request(PaymentMethod.MOMO)))
                .isInstanceOf(DataAccessResourceFailureException.class);

        // Verify
        verify(gateway, never()).charge(any());
        // verify that saveAndFlush() was called once
        // 1: create pending payment
        verify(paymentRepository, times(1)).saveAndFlush(any());
    }

    @Test
    @DisplayName("Pay should throw DataIntegrityViolationException when data too large")
    void pay_shouldThrowDataIntegrityViolationException_whenDataTooLarge() {
        // Arrange
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());

        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("data too large"));

        // Act + Assert
        assertThatThrownBy(() -> paymentService.pay(KEY, request(PaymentMethod.MOMO)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Verify
        verify(gateway, never()).charge(any());
        // verify that saveAndFlush() was called once
        // 1: create pending payment
        verify(paymentRepository, times(1)).saveAndFlush(any());
    }

    @Test
    @DisplayName("Pay should keep pending without throwing when gateway throws")
    void pay_shouldKeepPending_withoutThrowing_whenGatewayThrows() {
        // Arrange
        when(paymentRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());

        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(gateway.charge(any())).thenThrow(new RuntimeException("Gateway timeout"));

        // Act
        PaymentResponse response = paymentService.pay(KEY, request(PaymentMethod.MOMO));

        // Assert
        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);

        // Verify
        // verify that saveAndFlush() was called once
        // 1: create pending payment
        verify(paymentRepository, times(1)).saveAndFlush(any());
    }

    @Test
    @DisplayName("Pay should return existing payment when concurrent request inserted same key first")
    void pay_shouldReturnExistingPayment_whenConcurrentRequestInsertedSameKeyFirst() {
        // Arrange
        Payment existing = Payment.createPending(KEY, 1L, PaymentMethod.MOMO,
                new BigDecimal("100000"), "VND", Instant.now());
        existing.markSuccess("tx-existing", Instant.now());
        // stub findByIdempotencyKey() first time to return empty optional
        // second time return existing payment
        when(paymentRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));

        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenThrow(createConstraintViolationException(
                        "PUBLIC.UK_PAYMENT_IDEMPOTENCY_KEY INDEX PUBLIC.UK_PAYMENT_IDEMPOTENCY_KEY_INDEX_5"));

        // Act
        PaymentResponse response = paymentService.pay(KEY, request(PaymentMethod.MOMO));

        // Assert
        assertThat(response.transactionId()).isEqualTo("tx-existing");
        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCESS);

        // Verify
        verify(gateway, never()).charge(any());
        verify(paymentRepository, times(2)).findByIdempotencyKey(KEY);
    }

    @Test
    @DisplayName("Pay should throw IllegalStateException when same payment method")
    void pay_shouldThrowIllegalStateException_whenSamePaymentMethod() {
        // Arrange
        PaymentGateway other = mock(PaymentGateway.class);
        when(other.supportedMethods()).thenReturn(Set.of(PaymentMethod.MOMO));

        assertThatThrownBy(() -> new PaymentService(paymentRepository, List.of(gateway, other)))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Create a mock constraint violation exception for testing
     * 
     * @param constraintName
     * @return DataIntegrityViolationException
     */
    private DataIntegrityViolationException createConstraintViolationException(String constraintName) {
        return new DataIntegrityViolationException("duplicate",
                new ConstraintViolationException("duplicate", null, constraintName));
    }
}
