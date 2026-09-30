package com.example.AviaryService.util;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.example.AviaryService.services.AlertLevel;

// Turns a Service Timeline row's stored due-date / due-hours strings into a
// number of days / hours remaining, and classifies that against a user's alert
// thresholds, and formats the Time Left text. Mirrors calculateTimeLeft in
// dashboard.js (client-side). See docs/ALERTS_SPEC.md.
public final class DueDates {

    private DueDates() {
    }

    // Whole days from today until dueDateDate (negative = overdue). null when the
    // string is blank or unparseable. dueDateDate is stored ISO ("2026-09-10");
    // legacy rows may append hours ("2026-09-10 12.5") so only the first token
    // is parsed.
    public static Long daysUntil(String dueDateDate, LocalDate today) {
        LocalDate due = parseDate(dueDateDate);
        if (due == null) {
            return null;
        }
        return ChronoUnit.DAYS.between(today, due);
    }

    // Hours of time-in-service remaining until the item is due (negative =
    // overdue). null when either value is missing/unparseable.
    public static Double hoursUntil(String dueDateHours, Double currentTimeInService) {
        Double due = Parsing.parseDoubleOrNull(trimFirstToken(dueDateHours));
        if (due == null || currentTimeInService == null) {
            return null;
        }
        return due - currentTimeInService;
    }

    // The item's alert level: the worse of its date-based and hours-based
    // standing. An item with neither a due date nor due hours is OK.
    public static AlertLevel classify(String dueDateDate, String dueDateHours,
                                      Double currentTimeInService, LocalDate today,
                                      int leadTimeDays, int leadTimeHours) {
        AlertLevel level = AlertLevel.OK;

        Long days = daysUntil(dueDateDate, today);
        if (days != null) {
            if (days < 0) {
                level = AlertLevel.OVERDUE;
            } else if (days <= leadTimeDays) {
                level = AlertLevel.DUE_SOON;
            }
        }

        Double hours = hoursUntil(dueDateHours, currentTimeInService);
        if (hours != null) {
            AlertLevel hoursLevel = hours < 0 ? AlertLevel.OVERDUE
                : (hours <= leadTimeHours ? AlertLevel.DUE_SOON : AlertLevel.OK);
            if (hoursLevel.worseThan(level)) {
                level = hoursLevel;
            }
        }

        return level;
    }

    // The Time Left text shown on the dashboard, print view and PDF, computed
    // fresh from the due date / due hours (never the stored timeLeft column,
    // which goes stale). Calendar line first, hours line second, "N/A" if
    // neither. MUST stay in sync with calculateTimeLeft in dashboard.js.
    public static String formatTimeLeft(String dueDateDate, String dueDateHours,
                                        LocalDate today, Double currentTimeInService) {
        StringBuilder sb = new StringBuilder();
        LocalDate due = parseDate(dueDateDate);
        if (due != null) {
            sb.append(formatCalendarTimeLeft(due, today));
        }
        Double hours = hoursUntil(dueDateHours, currentTimeInService);
        if (hours != null) {
            double rounded = Math.round(hours * 10.0) / 10.0;
            if (sb.length() > 0) sb.append('\n');
            sb.append(plainNumber(Math.abs(rounded))).append(rounded < 0 ? " hours overdue" : " hours left");
        }
        return sb.length() == 0 ? "N/A" : sb.toString();
    }

    // "Smart" calendar Time Left -- same rules as formatCalendarTimeLeft in
    // dashboard.js:
    //   under 60 days  -> "45 days left"
    //   under 1 year   -> "5 mo 12 days left" ("5 mo left" on an exact month)
    //   1 year or more -> "1 yr 1 mo left" (days dropped)
    // Overdue reads the same with "overdue". Calendar months (Period.between).
    public static String formatCalendarTimeLeft(LocalDate due, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, due);
        String suffix = days < 0 ? "overdue" : "left";
        long span = Math.abs(days);
        if (span < 60) {
            return span + (span == 1 ? " day " : " days ") + suffix;
        }
        java.time.Period p = days < 0 ? java.time.Period.between(due, today) : java.time.Period.between(today, due);
        StringBuilder sb = new StringBuilder();
        if (p.getYears() > 0) sb.append(p.getYears()).append(p.getYears() == 1 ? " yr" : " yrs");
        if (p.getMonths() > 0) sb.append(sb.length() > 0 ? " " : "").append(p.getMonths()).append(" mo");
        if (p.getYears() == 0 && p.getDays() > 0) {
            sb.append(sb.length() > 0 ? " " : "").append(p.getDays()).append(p.getDays() == 1 ? " day" : " days");
        }
        return sb.append(' ').append(suffix).toString();
    }

    // 15.2 -> "15.2", 50.0 -> "50" -- matches how JavaScript prints numbers,
    // so the server and browser produce identical text.
    private static String plainNumber(double v) {
        return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private static LocalDate parseDate(String raw) {
        String token = trimFirstToken(raw);
        if (token == null) {
            return null;
        }
        try {
            return LocalDate.parse(token);
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimFirstToken(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        int sp = s.indexOf(' ');
        return sp < 0 ? s : s.substring(0, sp);
    }
}
