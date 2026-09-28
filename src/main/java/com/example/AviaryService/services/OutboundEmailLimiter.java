package com.example.AviaryService.services;

import java.time.Duration;

import org.springframework.stereotype.Service;

import com.example.AviaryService.entity.User;
import com.example.AviaryService.util.SlidingWindowLimiter;

// Caps how many user-triggered emails one account can send per hour: alert
// recipient invites/resends, "Send alert now", and the dashboard PDF email.
// These all go to addresses the user typed, so without a cap one account
// could flood any inbox through our SendGrid sender. Scheduled alert digests
// are not counted -- the app sends those, not the user.
@Service
public class OutboundEmailLimiter {

    static final int MAX_PER_HOUR = 10;

    private final SlidingWindowLimiter limiter = new SlidingWindowLimiter(MAX_PER_HOUR, Duration.ofHours(1));

    public void acquire(User user) {
        if (!limiter.tryAcquire(String.valueOf(user.getId()))) {
            throw new RateLimitedException("You've sent a lot of emails in the last hour. Please try again later.");
        }
    }
}
