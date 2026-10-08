package com.ecommerce.paymentservice.dto;

import java.math.BigDecimal;

import com.ecommerce.paymentservice.domain.PaymentMethod;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PaymentRequest(
                @NotNull(message = "Order ID is required") Long orderId,

                @NotNull @DecimalMin(value = "0.01", inclusive = true) @Digits(integer = 17, fraction = 2) BigDecimal amount,

                @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,

                @NotNull PaymentMethod paymentMethod,

                @NotBlank String paymentToken,

                String returnUrl) {

}
