package com.example.AviaryService.services.alerts;

import java.util.List;

import com.example.AviaryService.entity.Aircraft;

final class AlertText {

    // Shared wording for alert emails, so every email names the owner and
    // aircraft the same way, and never shows a blank if one is missing.
    // Alerts are account-wide, so these take the whole fleet (ordered by id).
    private AlertText() {}

    // The first owner name set on any aircraft, first aircraft first.
    static String owner(List<Aircraft> fleet) {
        for (Aircraft a : fleet) {
            String name = a.getOwnerName();
            if (name != null && !name.isBlank()) return name.trim();
        }
        return "The aircraft owner";
    }

    // One aircraft: its tail number. Several: "your fleet (3 aircraft)".
    static String tail(List<Aircraft> fleet) {
        if (fleet.size() > 1) return "your fleet (" + fleet.size() + " aircraft)";
        return fleet.isEmpty() ? "your aircraft" : tailOf(fleet.get(0), "your aircraft");
    }

    static String tailOf(Aircraft aircraft, String fallback) {
        String tail = aircraft.getTailNumber();
        return tail == null || tail.isBlank() ? fallback : tail.trim();
    }

    static boolean anyOwnerName(List<Aircraft> fleet) {
        return fleet.stream().anyMatch(a -> a.getOwnerName() != null && !a.getOwnerName().isBlank());
    }

    static boolean anyTailNumber(List<Aircraft> fleet) {
        return fleet.stream().anyMatch(a -> a.getTailNumber() != null && !a.getTailNumber().isBlank());
    }
}
