package com.example.AviaryService.services;

import java.time.Duration;
import java.util.Locale;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Service;

import com.example.AviaryService.util.SlidingWindowLimiter;

// Slows down password guessing: after MAX_FAILURES wrong passwords for a
// username within WINDOW, that username is locked until the oldest failure
// ages out. SecurityConfig's UserDetailsService marks the account locked, so
// Spring rejects the attempt before even checking the password. A successful
// login clears the count. Listens to the events Spring Security publishes.
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
