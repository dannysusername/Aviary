package com.example.AviaryService.util;

import java.util.Locale;
import java.util.regex.Pattern;

// Input limits shared by the controllers/services. Text columns are the JPA
// default varchar(255); anything longer used to fail at the database and come
// back as a 500 instead of a readable 400.
public final class Validation {

    public static final int MAX_TEXT = 255;

    // Letters, digits, spaces and dashes (e.g. "N731KR", "G-ABCD", "C GXYZ").
    private static final Pattern TAIL_NUMBER = Pattern.compile("[A-Za-z0-9 \\-]+");

    private Validation() {}

    public static void maxLength(String label, String value, int max) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(label + " must be " + max + " characters or fewer.");
        }
    }

    public static void maxLength(String label, String value) {
        maxLength(label, value, MAX_TEXT);
    }

    // Upper-cased with spaces removed ("n 731 kr" -> "N731KR"); dashes are
    // kept ("G-ABCD"). Null stays null.
    public static String normalizeTailNumber(String raw) {
        return raw == null ? null : raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    // The value two tail numbers are compared by: upper-cased with all
    // whitespace removed, so "n 731 kr" and "N731KR" collide. Null when blank,
    // so an account can hold several aircraft with no tail number yet.
    public static String tailNumberKey(String raw) {
        if (raw == null) return null;
        String key = raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }

    public static void requireValidTailNumber(String raw, boolean required) {
        String tail = raw == null ? "" : raw.trim();
        if (tail.isEmpty()) {
            if (required) throw new IllegalArgumentException("Enter a tail number.");
            return;
        }
        maxLength("Tail number", tail);
        if (!TAIL_NUMBER.matcher(tail).matches()) {
            throw new IllegalArgumentException("Use letters, numbers, spaces, or dashes.");
        }
    }
}
