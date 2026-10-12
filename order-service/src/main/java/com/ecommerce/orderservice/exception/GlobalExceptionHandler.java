package com.ecommerce.orderservice.exception;

import feign.FeignException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Maps exceptions to the JSON error format shared by all services.
 *
 * <p>Error responses must never expose internal details (service URLs, Java method signatures, raw
 * downstream payloads). Those details are logged server-side instead, where they help debugging
 * without telling an outside client how the system is built.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final String GENERIC_DOWNSTREAM_REJECTION = "Request rejected by a dependent service";

    private final ObjectMapper objectMapper;

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

    /**
     * Handles errors returned by downstream services through Feign.
     *
     * <p>4xx keeps the downstream status and its business message (e.g. "out of stock"), because the
     * client can act on it. {@code ex.getMessage()} is NOT forwarded: Feign builds it from the internal
     * request URL and the client method signature.
     */
    @ExceptionHandler(FeignException.class)
    public ResponseEntity<Map<String, Object>> handlerFeignClientException(FeignException ex) {
        log.warn("Downstream call failed: {}", ex.getMessage());
        if (ex.status() >= 400 && ex.status() < 500) {
            return ResponseEntity.status(ex.status()).body(Map.of(
                    "timestamp", Instant.now().toString(),
                    "status", ex.status(),
                    "error", "Downstream Error",
                    "message", downstreamMessage(ex)));
        }
        // 5xx error -> downstream unavailable
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
                .collect(java.util.stream.Collectors.toMap(f -> f.getField(), f -> f.getDefaultMessage(),
                        (existing, replacement) -> existing));

        Map<String, Object> body = Map.of(
                "timestamp", Instant.now().toString(),
                "status", 400,
                "error", "Bad Request",
                "errors", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handlerServiceUnavailable(ServiceUnavailableException ex) {
        // The exception message is the internal service name: useful in logs, meaningless to a client
        log.warn("Dependency unavailable: {}", ex.getMessage(), ex);
        Map<String, Object> body = Map.of(
                "timestamp", Instant.now().toString(),
                "status", 503,
                "error", "Service Unavailable",
                "message", "Unable to process request at this time. Please try again later.");

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /**
     * Extracts the {@code message} field that every service puts in its error body.
     *
     * <p>Falls back to a generic text when the body is empty, not JSON, or has no {@code message}
     * (e.g. a validation error carrying an {@code errors} map). Field names in such a map describe the
     * downstream service's internal contract, not the request the client sent, so they are not forwarded.
     */
    private String downstreamMessage(FeignException ex) {
        try {
            JsonNode message = objectMapper.readTree(ex.contentUTF8()).get("message");
            if (message != null && message.isString() && !message.asString().isBlank()) {
                return message.asString();
            }
        } catch (JacksonException e) {
            // Body is not JSON (e.g. an HTML error page from a proxy): fall through to the generic text
        }
        return GENERIC_DOWNSTREAM_REJECTION;
    }
}
