package com.example.AviaryService.services.stats;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.AlertSendLogRepository;
import com.example.AviaryService.repositories.FlightLogRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.services.stats.PublicStatsSnapshot.SignupEntry;

// Builds the public numbers shown on DanielIbarra.com and keeps them in memory.
//
// Counting on every page view would let a busy (or scraped) portfolio page put
// load on Aviary's database. So one snapshot is kept and served until it's
// older than the TTL (15 minutes by default). The next request after that
// recomputes it -- one thread at a time; everyone else keeps getting the old
// one. There's no timer: if nobody looks, nothing runs, so the stats never
// wake the Neon database on their own. See specs/004-public-aviary-stats/research.md R2.
//
// Every account is counted for now, test accounts included (spec FR-009).
// If they're left out later, this is the one place to change.
@Service
public class PublicStatsService {

    private static final Logger log = LoggerFactory.getLogger(PublicStatsService.class);

    static final String SIGNUP_LABEL = "New pilot joined";
    static final int MAX_RECENT_SIGNUPS = 10;

    private final UserRepository userRepository;
    private final AircraftRepository aircraftRepository;
    private final FlightLogRepository flightLogRepository;
    private final AlertSendLogRepository alertSendLogRepository;
    private final PublicStatsProperties props;
    private final Clock clock;

    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile PublicStatsSnapshot cached;

    @Autowired
    public PublicStatsService(UserRepository userRepository, AircraftRepository aircraftRepository,
                              FlightLogRepository flightLogRepository, AlertSendLogRepository alertSendLogRepository,
                              PublicStatsProperties props) {
        this(userRepository, aircraftRepository, flightLogRepository, alertSendLogRepository, props, Clock.systemUTC());
    }

    PublicStatsService(UserRepository userRepository, AircraftRepository aircraftRepository,
                       FlightLogRepository flightLogRepository, AlertSendLogRepository alertSendLogRepository,
                       PublicStatsProperties props, Clock clock) {
        this.userRepository = userRepository;
        this.aircraftRepository = aircraftRepository;
        this.flightLogRepository = flightLogRepository;
        this.alertSendLogRepository = alertSendLogRepository;
        this.props = props;
        this.clock = clock;
    }

    // The current public numbers. Fresh snapshot -> returned as is, no queries.
    public PublicStatsSnapshot current() {
        PublicStatsSnapshot snap = cached;
        if (snap != null && !isStale(snap)) {
            return snap;
        }
        if (snap != null) {
            // Stale: one thread refreshes, the rest serve the old numbers now.
            if (!refreshLock.tryLock()) {
                return snap;
            }
        } else {
            // Nothing to serve yet: wait for whoever is computing the first one.
            refreshLock.lock();
        }
        try {
            PublicStatsSnapshot latest = cached;
            if (latest != null && !isStale(latest)) {
                return latest; // another thread refreshed while we waited
            }
            try {
                cached = compute();
                return cached;
            } catch (RuntimeException e) {
                if (latest != null) {
                    log.warn("Public stats refresh failed; serving snapshot from {}", latest.generatedAt(), e);
                    return latest;
                }
                throw new PublicStatsUnavailableException(e);
            }
        } finally {
            refreshLock.unlock();
        }
    }

    private boolean isStale(PublicStatsSnapshot snap) {
        return Duration.between(snap.generatedAt(), clock.instant()).compareTo(props.ttl()) >= 0;
    }

    private PublicStatsSnapshot compute() {
        Instant now = clock.instant();

        // Hours flown = time in service. Block time is empty on production
        // flights, so it would always show 0 (research R6).
        Double hours = flightLogRepository.sumHoursFlown();
        double hoursFlown = hours == null ? 0.0 : Math.round(hours * 10.0) / 10.0;

        // Accounts made before signup dates were recorded have no createdAt;
        // they count above but never show up here (no guessed dates, R3).
        List<SignupEntry> recent = userRepository
                .findRecentSignupTimes(PageRequest.of(0, MAX_RECENT_SIGNUPS))
                .stream()
                .map(ts -> new SignupEntry(SIGNUP_LABEL, ts.atZone(props.zone()).toLocalDate().toString()))
                .toList();

        return new PublicStatsSnapshot(
                userRepository.count(),
                aircraftRepository.count(),
                flightLogRepository.count(),
                hoursFlown,
                alertSendLogRepository.count(),
                userRepository.countByCreatedAtAfter(now.minus(Duration.ofDays(7))),
                userRepository.countByCreatedAtAfter(now.minus(Duration.ofDays(30))),
                recent,
                now);
    }
}
