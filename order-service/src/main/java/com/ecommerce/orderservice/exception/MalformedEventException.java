package com.ecommerce.orderservice.exception;

/**
 * A received event can never be processed, however often it is retried (a "poison message"):
 * unreadable JSON or a payload that breaks the contract. Sent straight to the dead-letter topic.
 */
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }

    public MalformedEventException(String message) {
        super(message);
    }
}
