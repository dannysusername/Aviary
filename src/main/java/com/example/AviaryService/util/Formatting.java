package com.example.AviaryService.util;

public class Formatting {
    
    private Formatting() {}

    public static String formatHours(double hours) {
        return hours == Math.floor(hours)
            ? Long.toString((long) hours)
            : Double.toString(hours);
    }

    public static String buildDateHoursString(java.time.LocalDate date, Double hours) {
        StringBuilder sb = new StringBuilder();
        if (date != null) sb.append(date.toString());
        if (hours != null) {
            if (sb.length() > 0) sb.append(' ');
            // Trim trailing zeros: 100.0 -> "100", 100.5 -> "100.5"
            sb.append(hours == Math.floor(hours)
                ? Long.toString((long) (double) hours)
                : Double.toString(hours));
        }
        return sb.toString();
    }
}
