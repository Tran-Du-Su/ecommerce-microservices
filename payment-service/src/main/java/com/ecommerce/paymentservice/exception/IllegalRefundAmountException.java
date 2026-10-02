package com.ecommerce.paymentservice.exception;

public class IllegalRefundAmountException extends RuntimeException {
    public IllegalRefundAmountException(String transactionId) {
        super("Refund amount for transaction " + transactionId + " is illegal");
    }
}
