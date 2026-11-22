package com.example.AviaryService.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

// A clock tests can move: set(...) jumps to a moment, advance(...) moves
// forward. Lets alert tests run the real scheduler over simulated days.
public class MutableClock extends Clock {

    private volatile Instant now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(start, ZoneOffset.UTC);
    }

    private MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public void set(Instant instant) {
        this.now = instant;
    }

    public void advance(Duration d) {
        this.now = now.plus(d);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    // Shares this clock's time; only the zone differs.
    @Override
    public Clock withZone(ZoneId z) {
        MutableClock parent = this;
        return new Clock() {
            @Override public Instant instant() { return parent.instant(); }
            @Override public ZoneId getZone() { return z; }
            @Override public Clock withZone(ZoneId other) { return parent.withZone(other); }
        };
    }
}
