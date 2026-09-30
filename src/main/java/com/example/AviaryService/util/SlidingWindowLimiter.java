package com.example.AviaryService.util;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Counts events per key over a rolling time window (e.g. "10 emails per user
// per hour", "5 failed logins per username per 15 minutes"). In memory, so the
// counts reset on restart and aren't shared between dynos -- fine for a single
// Heroku web dyno. Move to the database if the app ever scales out.
public class SlidingWindowLimiter {

    // Past this many tracked keys, drop the ones with no recent events so a
    // flood of distinct keys (e.g. random usernames) can't grow memory forever.
    private static final int SWEEP_THRESHOLD = 10_000;

    private final int max;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> events = new ConcurrentHashMap<>();

    public SlidingWindowLimiter(int max, Duration window) {
        this(max, window, Clock.systemUTC());
    }

    public SlidingWindowLimiter(int max, Duration window, Clock clock) {
        this.max = max;
        this.window = window;
        this.clock = clock;
    }

    // Records an event and returns true if the key is still under the limit;
    // returns false (recording nothing) if it's already at the limit.
    public boolean tryAcquire(String key) {
        Deque<Instant> q = events.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q);
            if (q.size() >= max) {
                return false;
            }
            q.addLast(clock.instant());
        }
        sweepIfLarge();
        return true;
    }

    // Records an event with no limit check (e.g. a failed login attempt).
    public void record(String key) {
        Deque<Instant> q = events.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q);
            q.addLast(clock.instant());
        }
        sweepIfLarge();
    }

    public boolean isLimited(String key) {
        Deque<Instant> q = events.get(key);
        if (q == null) {
            return false;
        }
        synchronized (q) {
            prune(q);
            return q.size() >= max;
        }
    }

    public void reset(String key) {
        events.remove(key);
    }

    private void prune(Deque<Instant> q) {
        Instant cutoff = clock.instant().minus(window);
        while (!q.isEmpty() && !q.peekFirst().isAfter(cutoff)) {
            q.pollFirst();
        }
    }

    private void sweepIfLarge() {
        if (events.size() <= SWEEP_THRESHOLD) {
            return;
        }
        events.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                prune(e.getValue());
                return e.getValue().isEmpty();
            }
        });
    }
}
