package com.example.AviaryService.services;

import java.time.Duration;
import java.util.Locale;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Service;

import com.example.AviaryService.util.SlidingWindowLimiter;

// Stops password guessing. After MAX_FAILURES wrong passwords for a username
// within WINDOW, that username is locked until the oldest failure ages out.
// A successful login resets the count. SecurityConfig checks the lock before
// even looking at the password.
@Service
public class LoginAttemptService {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final SlidingWindowLimiter failures = new SlidingWindowLimiter(MAX_FAILURES, WINDOW);

    public boolean isLocked(String username) {
        return username != null && failures.isLimited(key(username));
    }

    @EventListener
    public void onFailure(AuthenticationFailureBadCredentialsEvent event) {
        Object name = event.getAuthentication().getPrincipal();
        if (name instanceof String username) {
            failures.record(key(username));
        }
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        failures.reset(key(event.getAuthentication().getName()));
    }

    private static String key(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
