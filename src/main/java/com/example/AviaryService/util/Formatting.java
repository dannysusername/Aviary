package com.example.AviaryService.util;

public class Formatting {

    private Formatting() {}

    // Real-world hour readings never exceed hundredths (block time reads to
    // 0.1, time in service to 0.01, AeroAPI is minute precision). Repeated double addition in
    // HoursService.recomputeChain otherwise leaves artifacts like
    // "2.0433333333333334". One home for the 2dp rule.
    public static double roundHours(double hours) {
        return Math.round(hours * 100.0) / 100.0;
    }

    public static Double roundHoursOrNull(Double hours) {
        return hours == null ? null : roundHours(hours);
    }

    public static String formatHours(double hours) {
        double r = roundHours(hours);
        // n/100.0 stringifies as its shortest 2dp form, so no trailing zeros
        // to trim: 2.50 -> "2.5", 2.04 -> "2.04", 2.00 -> "2".
        return r == Math.floor(r)
            ? Long.toString((long) r)
            : Double.toString(r);
    }

    // Matches formatCycleDisplay in dashboard.js exactly -- e.g. "3 months",
    // "100 hrs", "3 months / 100 hrs", or "" if neither is set.
    public static String formatCycle(Integer calValue, String calUnit, Double hours) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (calValue != null && calValue > 0 && calUnit != null) {
            parts.add(calValue + " " + calUnit.toLowerCase());
        }
        if (hours != null && hours > 0) {
            parts.add(formatHours(hours) + " hrs");
        }
        return String.join(" / ", parts);
    }

    public static String buildDateHoursString(java.time.LocalDate date, Double hours) {
        StringBuilder sb = new StringBuilder();
        if (date != null) sb.append(date.toString());
        if (hours != null) {
            if (sb.length() > 0) sb.append(' ');
            // Round to 2dp and trim trailing zeros: 100.0 -> "100", 100.5 -> "100.5"
            double r = roundHours(hours);
            sb.append(r == Math.floor(r)
                ? Long.toString((long) r)
                : Double.toString(r));
        }
        return sb.toString();
    }
}
