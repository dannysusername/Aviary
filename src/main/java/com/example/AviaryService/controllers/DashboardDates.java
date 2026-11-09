package com.example.AviaryService.controllers;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

// The browser's local date, when it sends one, so Time Left and due counts
// match what the owner sees even when the server's UTC date differs. Only
// trusted within a day of the server's date; anything else -> server date.
final class DashboardDates {

    private DashboardDates() {}

    static LocalDate asOf(String today) {
        LocalDate serverToday = LocalDate.now();
        try {
            LocalDate clientToday = today == null ? null : LocalDate.parse(today);
            if (clientToday != null && Math.abs(ChronoUnit.DAYS.between(serverToday, clientToday)) <= 1) {
                return clientToday;
            }
        } catch (DateTimeParseException ignored) {
            // bad value -> server date
        }
        return serverToday;
    }
}
