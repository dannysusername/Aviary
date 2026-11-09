package com.example.AviaryService.services.stats;

// No stats have been computed yet and computing them just failed (e.g. the
// database is down). PublicStatsController turns this into a 503.
public class PublicStatsUnavailableException extends RuntimeException {
    public PublicStatsUnavailableException(Throwable cause) {
        super("Public stats are unavailable", cause);
    }
}
