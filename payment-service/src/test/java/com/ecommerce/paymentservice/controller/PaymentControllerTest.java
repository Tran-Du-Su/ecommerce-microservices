package com.ecommerce.paymentservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ecommerce.paymentservice.domain.PaymentMethod;
import com.ecommerce.paymentservice.domain.PaymentStatus;
import com.ecommerce.paymentservice.dto.PaymentResponse;
import com.ecommerce.paymentservice.exception.PaymentAlreadyActiveException;
import com.ecommerce.paymentservice.exception.UnsupportedPaymentMethodException;
import com.ecommerce.paymentservice.service.PaymentService;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    private static final String KEY = "order-1-key";
    private static final String VALID_BODY = """
            {
              "orderId": 1,
              "amount": 100000,
              "currency": "VND",
              "paymentMethod": "MOMO",
              "paymentToken": "TOKEN_OK"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    // A. Test map status to HTTP code
    @Nested
    class TestHttpStatusCode {
        @Test
        void pay_shouldReturn201WithBody_whenPaymentSucceeds() throws Exception {
            when(paymentService.pay(eq(KEY), any())).thenReturn(response(PaymentStatus.SUCCESS, "tx-123"));

            mockMvc.perform(post("/api/payments")
                    .header("Idempotency-Key", KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("SUCCESS"))
                    .andExpect(jsonPath("$.transactionId").value("tx-123"));

            verify(paymentService).pay(eq(KEY), any());
        }

        @Test
        void pay_shouldReturn201WithBody_whenPaymentFailed() throws Exception {
            when(paymentService.pay(eq(KEY), any())).thenReturn(response(PaymentStatus.FAILED, "tx-123"));

            mockMvc.perform(post("/api/payments")
                    .header("Idempotency-Key", KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("FAILED"))
                    .andExpect(jsonPath("$.transactionId").value("tx-123"));

            verify(paymentService).pay(eq(KEY), any());
        }

        @Test
        void pay_shouldReturn202WithBody_whenPaymentPending() throws Exception {
            when(paymentService.pay(eq(KEY), any())).thenReturn(response(PaymentStatus.PENDING, null));

            mockMvc.perform(post("/api/payments")
                    .header("Idempotency-Key", KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("PENDING"));

            verify(paymentService).pay(eq(KEY), any());
        }

        @Test
        void pay_shouldReturn201WithBody_whenPaymentRefunded() throws Exception {
            when(paymentService.pay(eq(KEY), any())).thenReturn(response(PaymentStatus.REFUNDED, "tx-123"));

            mockMvc.perform(post("/api/payments")
                    .header("Idempotency-Key", KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("REFUNDED"))
                    .andExpect(jsonPath("$.transactionId").value("tx-123"));

            verify(paymentService).pay(eq(KEY), any());
        }
    }

    // B. Test Invalid input → 400, service not called.
    @Nested
    class TestInvalidInput {
        @Test
        void pay_shouldReturn400InProjectFormat_withoutCallingService_whenHeaderMissing() throws Exception {
            mockMvc.perform(post("/api/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value("Idempotency-Key is required"));

            verify(paymentService, never()).pay(any(), any());
        }

        @Test
        void pay_shouldReturn400InProjectFormat_withoutCallingService_whenHeaderIsEmpty() throws Exception {
            mockMvc.perform(post("/api/payments")
                    .header("Idempotency-Key", "")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errors['Idempotency-Key']").value("Idempotency-Key must not be blank"));

            verify(paymentService, never()).pay(any(), any());
        }

        @Test
        void pay_shouldReturn400InProjectFormat_withoutCallingService_whenHeaderIsTooLong() throws Exception {
            mockMvc.perform(post("/api/payments")
                    .header("Idempotency-Key", "a".repeat(256))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errors['Idempotency-Key']")
                            .value("Idempotency-Key must not be longer than 255 characters"));

            verify(paymentService, never()).pay(any(), any());
        }

        @Test
        void pay_shouldReturn400InProjectFormat_withoutCallingService_whenAmountIsInvalid() throws Exception {
            mockMvc.perform(post("/api/payments")
                    .header("Idempotency-Key", KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY.replace("100000", "10.555")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errors.amount").exists());

            verify(paymentService, never()).pay(any(), any());
        }

        @Test
        void pay_shouldReturn400InProjectFormat_withoutCallingService_whenCurrencyIsInvalid() throws Exception {
            mockMvc.perform(post("/api/payments")
                    .header("Idempotency-Key", KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY.replace("\"VND\"", "\"usd\"")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errors.currency").exists());

            verify(paymentService, never()).pay(any(), any());
        }

        @Test
        void pay_shouldReturn400InProjectFormat_withoutCallingService_whenOrderIdIsInvalid() throws Exception {
            mockMvc.perform(post("/api/payments")
                    .header("Idempotency-Key", KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY.replace("\"orderId\": 1,", "")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errors.orderId")
                            .value("Order ID is required"));

            verify(paymentService, never()).pay(any(), any());
        }

        @Test
        void pay_shouldReturn400InProjectFormat_withoutCallingService_whenInvalidBody() throws Exception {
            mockMvc.perform(post("/api/payments")
                    .header("Idempotency-Key", KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"orderId\":123,}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value("Malformed request body"));

            verify(paymentService, never()).pay(any(), any());
        }
    }

    // C. Test handler when service throw Exception
    @Nested
    class TestServiceExceptions {
        @Test
        void pay_shouldReturn409InProjectFormat_whenServiceThrowPaymentAlreadyActiveException()
                throws Exception {
            when(paymentService.pay(eq(KEY), any()))
                    .thenThrow(new PaymentAlreadyActiveException(1L));

            mockMvc.perform(post("/api/payments")
                    .header("Idempotency-Key", KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.error").value("Conflict"))
                    .andExpect(jsonPath("$.message").exists());

            verify(paymentService).pay(eq(KEY), any());
        }

        @Test
        void pay_shouldReturn400InProjectFormat_whenServiceThrowUnsupportedPaymentMethodException()
                throws Exception {
            when(paymentService.pay(eq(KEY), any()))
                    .thenThrow(new UnsupportedPaymentMethodException(PaymentMethod.STRIPE));

            mockMvc.perform(post("/api/payments")
                    .header("Idempotency-Key", KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("Bad Request"))
                    .andExpect(jsonPath("$.message").exists());

            verify(paymentService).pay(eq(KEY), any());
        }
    }

    /**
     * Create a mock payment response for testing
     * 
     * @param status        The payment status to return
     * @param transactionId The transaction ID to return
     * @return PaymentResponse
     */
    private PaymentResponse response(PaymentStatus status, String transactionId) {
        return new PaymentResponse(1L, KEY, 1L, new BigDecimal("100000.00"), "VND", PaymentMethod.MOMO,
                status, transactionId, null, Instant.now(), Instant.now());
    }

}
