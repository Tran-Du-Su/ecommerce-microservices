package com.ecommerce.orderservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ecommerce.orderservice.domain.OrderStatus;
import com.ecommerce.orderservice.dto.OrderResponse;
import com.ecommerce.orderservice.exception.ServiceUnavailableException;
import com.ecommerce.orderservice.service.OrderService;

import feign.FeignException;
import feign.Request;
import feign.Response;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    private static final String VALID_BODY = """
            {
              "userId": 1,
              "items": [ { "productId": 100, "quantity": 2 } ],
              "paymentMethod": "MOMO",
              "paymentToken": "TOKEN_OK"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    void createOrder_shouldReturn201_whenOrderConfirmed() throws Exception {
        when(orderService.createOrder(any())).thenReturn(order(OrderStatus.CONFIRMED));

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void createOrder_shouldReturn201_whenPaymentDeclined() throws Exception {
        when(orderService.createOrder(any())).thenReturn(order(OrderStatus.CANCELLED));

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void createOrder_shouldReturn202_whenPaymentOutcomeUnknown() throws Exception {
        when(orderService.createOrder(any())).thenReturn(order(OrderStatus.PENDING));

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void createOrder_shouldReturn400_withoutCallingService_whenPaymentTokenMissing() throws Exception {
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY.replace("\"paymentToken\": \"TOKEN_OK\"", "\"paymentToken\": \"\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.paymentToken").value("Payment token is required"));

        verify(orderService, never()).createOrder(any());
    }

    @Test
    void createOrder_shouldForwardOnlyDownstreamMessage_whenDependencyRejectsRequest() throws Exception {
        when(orderService.createOrder(any())).thenThrow(feignError(400,
                "{\"timestamp\":\"t\",\"status\":400,\"error\":\"Bad Request\",\"message\":\"Malformed request body\"}"));

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"))
                .andExpect(jsonPath("$.message", not(containsString("http://"))));
    }

    @Test
    void createOrder_shouldUseGenericMessage_whenDownstreamBodyIsNotJson() throws Exception {
        when(orderService.createOrder(any())).thenThrow(feignError(409, "<html>Conflict</html>"));

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Request rejected by a dependent service"));
    }

    @Test
    void createOrder_shouldNotExposeServiceName_whenDependencyUnavailable() throws Exception {
        when(orderService.createOrder(any())).thenThrow(new ServiceUnavailableException("inventory-service", null));

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message", not(containsString("inventory-service"))));
    }

    /** Builds the exception Feign raises for a downstream response with the given status and body. */
    private FeignException feignError(int status, String body) {
        Request request = Request.create(Request.HttpMethod.POST, "http://payment-service/api/payments", Map.of(),
                null, StandardCharsets.UTF_8, null);
        Response response = Response.builder()
                .status(status)
                .reason("status " + status)
                .request(request)
                .headers(Map.of())
                .body(body, StandardCharsets.UTF_8)
                .build();
        return FeignException.errorStatus("PaymentClient#pay(String,PaymentRequest)", response);
    }

    private OrderResponse order(OrderStatus status) {
        return new OrderResponse(1L, 1L, new BigDecimal("1000000"), status, List.of());
    }
}
