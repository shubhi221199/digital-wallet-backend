package com.airtribe.wallet;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(WalletException.class)
    ResponseEntity<ApiError> handleWalletException(WalletException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(new ApiError(exception.code(), exception.getMessage(), requestId(request)));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ApiError("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header is required", requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        return request.getHeader("X-Request-Id") == null ? UUID.randomUUID().toString() : request.getHeader("X-Request-Id");
    }
}