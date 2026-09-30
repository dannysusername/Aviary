package com.example.AviaryService.util;

// Input limits shared by the controllers/services. Text columns are the JPA
// default varchar(255); anything longer used to fail at the database and come
// back as a 500 instead of a readable 400.
public final class Validation {

    public static final int MAX_TEXT = 255;

    private Validation() {}

    public static void maxLength(String label, String value, int max) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(label + " must be " + max + " characters or fewer.");
        }
    }

    public static void maxLength(String label, String value) {
        maxLength(label, value, MAX_TEXT);
    }
}
