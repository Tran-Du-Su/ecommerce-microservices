package com.ecommerce.orderservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ecommerce.orderservice.domain.OrderStatus;
import com.ecommerce.orderservice.dto.OrderResponse;
import com.ecommerce.orderservice.service.OrderService;

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

    private OrderResponse order(OrderStatus status) {
        return new OrderResponse(1L, 1L, new BigDecimal("1000000"), status, List.of());
    }
}
