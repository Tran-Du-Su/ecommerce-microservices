package com.ecommerce.orderservice.exception;

import feign.FeignException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handlerNotFound(ProductNotFoundException ex) {
        Map<String, Object> body = Map.of(
                "timestamp", Instant.now().toString(),
                "status", 404,
                "error", "Not Found",
                "message", ex.getMessage());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(OutOfStockException.class)
    public ResponseEntity<Map<String, Object>> handlerOutOfStock(OutOfStockException ex) {
        Map<String, Object> body = Map.of(
                "timestamp", Instant.now().toString(),
                "status", 409,
                "error", "Out Of Stock",
                "message", ex.getMessage());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(FeignException.class)
    public ResponseEntity<Map<String, Object>> handlerFeignClientException(FeignException ex) {
        if (ex.status() >= 400 && ex.status() < 500) {
            return ResponseEntity.status(ex.status()).body(Map.of(
                    "timestamp", Instant.now().toString(),
                    "status", ex.status(),
                    "error", "Downstream Error",
                    "message", "Request rejected by a dependent service: " + ex.getMessage()));
        }
        Map<String, Object> body = Map.of(
                "timestamp", Instant.now().toString(),
                "status", 503,
                "error", "Service Unavailable",
                "message", "Unable to process request at this time. Please try again later.");

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handlerMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        Map<String, Object> errors = ex.getBindingResult().getFieldErrors()
                .stream()
                .collect(java.util.stream.Collectors.toMap(f -> f.getField(), f -> f.getDefaultMessage()));

        Map<String, Object> body = Map.of(
                "timestamp", Instant.now().toString(),
                "status", 400,
                "error", "Bad Request",
                "errors", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
