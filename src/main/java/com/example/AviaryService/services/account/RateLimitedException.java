package com.example.AviaryService.services.account;

// The error thrown when a user does something too many times.
// GlobalExceptionHandler turns it into a 429 "too many requests" response.
public class RateLimitedException extends RuntimeException {
    public RateLimitedException(String message) {
        super(message);
    }
}
