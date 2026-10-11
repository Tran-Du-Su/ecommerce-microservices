package com.ecommerce.paymentservice.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ecommerce.paymentservice.dto.PaymentRequest;
import com.ecommerce.paymentservice.dto.PaymentResponse;
import com.ecommerce.paymentservice.service.PaymentService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentResponse> pay(
            @NotBlank(message = "Idempotency-Key must not be blank") @Size(max = 255, message = "Idempotency-Key must not be longer than 255 characters") @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {
        PaymentResponse response = paymentService.pay(idempotencyKey, request);
        HttpStatus status = switch (response.status()) {
            case SUCCESS, FAILED, REFUNDED -> HttpStatus.CREATED;
            case PENDING -> HttpStatus.ACCEPTED;
        };
        return ResponseEntity.status(status).body(response);
    }

}
