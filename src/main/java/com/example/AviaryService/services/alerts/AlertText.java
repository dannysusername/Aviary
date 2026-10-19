package com.example.AviaryService.services.alerts;

import com.example.AviaryService.entity.User;

final class AlertText {
    
    // Shared wording for alert emails, so every email names the owner and
    // aircraft the same way, and never shows a blank if one is missing.
    private AlertText() {}

    static String owner(User user) {
        String name = user.getOwnerName();
        return name == null || name.isBlank() ? "The aircraft owner" : name.trim();
    }

    static String tail(User user) {
        String tail = user.getTailNumber();
        return tail == null || tail.isBlank() ? "your aircraft" : tail.trim();
    }

}
