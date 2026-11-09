package com.example.AviaryService.controllers;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.AviaryService.services.stats.PublicStatsService;
import com.example.AviaryService.services.stats.PublicStatsUnavailableException;
import com.example.AviaryService.util.SlidingWindowLimiter;

import jakarta.servlet.http.HttpServletRequest;

// The live numbers on DanielIbarra.com's Aviary panel come from here.
//
// A small script on the portfolio page calls GET /api/public/stats from the
// visitor's browser. It's public (no login) and read-only: it returns only
// totals and anonymous signup dates, never anything about one account.
// SecurityConfig's publicApiFilterChain lets it through without a session and
// allows only DanielIbarra.com to read it from a browser (CORS).
//
// Public endpoints must be rate limited (constitution V): 60 calls per IP per
// 10 minutes. The numbers themselves are cached in PublicStatsService, so even
// heavy traffic doesn't reach the database more than once per 15 minutes.
// Contract: specs/004-public-aviary-stats/contracts/public-stats-api.md
@RestController
@RequestMapping("/api/public")
public class PublicStatsController {

    static final int MAX_PER_WINDOW = 60;
    static final Duration WINDOW = Duration.ofMinutes(10);

    private final PublicStatsService statsService;
    private final SlidingWindowLimiter limiter = new SlidingWindowLimiter(MAX_PER_WINDOW, WINDOW);

    public PublicStatsController(PublicStatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats(HttpServletRequest request) {
        // Behind Heroku's router this is the real client IP
        // (server.forward-headers-strategy=native in application-heroku.properties).
        if (!limiter.tryAcquire(request.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(WINDOW.toSeconds()))
                    .body(Map.of("error", "rate_limited"));
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(statsService.current());
    }

    // No numbers computed yet and the database can't be reached.
    @ExceptionHandler(PublicStatsUnavailableException.class)
    public ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "unavailable"));
    }
}
