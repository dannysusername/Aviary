package com.example.AviaryService.services.stats;

import java.time.Instant;
import java.util.List;

// The public numbers about Aviary at one moment, as sent to DanielIbarra.com.
// Only totals and anonymous, date-only signup lines. Nothing in here may point
// back at one account: no IDs, usernames, emails, tail numbers or per-user
// numbers (spec FR-008). Adding a field is a contract change -- see
// specs/004-public-aviary-stats/contracts/public-stats-api.md.
public record PublicStatsSnapshot(
        long users,
        long aircraft,
        long flights,
        double hoursFlown,
        long alertsSent,
        long signupsLast7Days,
        long signupsLast30Days,
        List<SignupEntry> recentSignups,
        Instant generatedAt) {

    public PublicStatsSnapshot {
        recentSignups = recentSignups == null ? List.of() : List.copyOf(recentSignups);
    }

    // One line in the "recent signups" feed: a fixed label and a YYYY-MM-DD date.
    public record SignupEntry(String label, String date) {}
}
