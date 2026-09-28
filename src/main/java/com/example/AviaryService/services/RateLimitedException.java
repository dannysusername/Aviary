package com.example.AviaryService.services;

// Thrown when a user hits a rate limit; GlobalExceptionHandler turns it into
// a 429 with this message.
public class RateLimitedException extends RuntimeException {
    public RateLimitedException(String message) {
        super(message);
    }
}
