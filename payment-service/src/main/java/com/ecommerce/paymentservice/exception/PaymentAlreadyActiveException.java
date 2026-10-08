package com.ecommerce.paymentservice.exception;

public class PaymentAlreadyActiveException extends RuntimeException {
    public PaymentAlreadyActiveException(Long orderId) {
        super("Payment already exists for order " + orderId);
    }
}
