package com.example.AviaryService.util;

public final class Parsing {

    private Parsing() {}

    public static Integer parseIntOrNull(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try { return Integer.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    public static Double parseDoubleOrNull(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try { return Double.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    public static String normalizeCalendarUnit(String raw) {
        if (raw == null) return null;
        String u = raw.trim().toUpperCase();
        if (u.isEmpty()) return null;
        if (u.equals("DAYS") || u.equals("MONTHS") || u.equals("YEARS")) return u;
        return null;
    }

}