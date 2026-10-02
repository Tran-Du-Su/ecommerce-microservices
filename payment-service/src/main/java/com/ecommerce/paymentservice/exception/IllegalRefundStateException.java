package com.ecommerce.paymentservice.exception;

public class IllegalRefundStateException extends RuntimeException {
    public IllegalRefundStateException(String transactionId) {
        super("Transaction is not in a SUCCESS state to be refunded: " + transactionId);
    }
}
