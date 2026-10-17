package com.example.AviaryService.services.alerts;

// How close a maintenance item is to being due: OK, DUE_SOON, or OVERDUE
// (least to most serious, in that order). An alert goes out when an item
// moves up from the level it last alerted at.
public enum AlertLevel {
    OK,
    DUE_SOON,
    OVERDUE;

    public boolean worseThan(AlertLevel other) {
        return this.ordinal() > other.ordinal();
    }

    public static AlertLevel parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return OK;
        }
        try {
            return AlertLevel.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return OK;
        }
    }

    // Stored on ServiceTimeline.alertLevel: null for OK (keeps existing rows
    // untouched), the enum name otherwise.
    public String stored() {
        return this == OK ? null : name();
    }
}
