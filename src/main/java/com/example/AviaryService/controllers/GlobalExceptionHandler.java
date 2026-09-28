package com.example.AviaryService.controllers;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.AviaryService.services.RateLimitedException;

// Last-resort mapping for errors that would otherwise surface as a bare 500.
// Bodies carry both "error" and "message" because the settings page reads
// the first and the dashboard reads the second.
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<Map<String, String>> rateLimited(RateLimitedException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body(e.getMessage()));
    }

    // Mostly text longer than its column. The explicit length checks catch the
    // common fields first; this covers anything they miss without leaking SQL.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> dataIntegrity(DataIntegrityViolationException e) {
        log.warn("Rejected write: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest().body(body("That value is too long or not allowed."));
    }

    private static Map<String, String> body(String message) {
        return Map.of("status", "error", "error", message, "message", message);
    }
}
