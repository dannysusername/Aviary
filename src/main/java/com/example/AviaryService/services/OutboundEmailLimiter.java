package com.example.AviaryService.services;

import java.time.Duration;

import org.springframework.stereotype.Service;

import com.example.AviaryService.entity.User;
import com.example.AviaryService.util.SlidingWindowLimiter;

// Limits how many emails one account can trigger per hour: alert invites and
// resends, "Send alert now", and emailing the PDF. Without it, someone could
// spam any inbox through our SendGrid account. The daily alert emails don't
// count, because the app sends those, not the user.
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
