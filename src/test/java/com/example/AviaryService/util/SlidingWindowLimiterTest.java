package com.example.AviaryService.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class SlidingWindowLimiterTest {

    // A clock the test can move forward.
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void allowsUpToMaxThenBlocks() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(3, Duration.ofHours(1), new TestClock());
        assertTrue(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("a"));
        assertFalse(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("b"), "keys are counted separately");
    }

    @Test
    void oldEventsAgeOut() {
        TestClock clock = new TestClock();
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(2, Duration.ofMinutes(15), clock);
        limiter.record("a");
        limiter.record("a");
        assertTrue(limiter.isLimited("a"));

        clock.now = clock.now.plus(Duration.ofMinutes(16));
        assertFalse(limiter.isLimited("a"));
    }

    @Test
    void resetClearsKey() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1, Duration.ofHours(1), new TestClock());
        limiter.record("a");
        assertTrue(limiter.isLimited("a"));
        limiter.reset("a");
        assertFalse(limiter.isLimited("a"));
    }
}
