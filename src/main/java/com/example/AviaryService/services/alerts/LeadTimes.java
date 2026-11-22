package com.example.AviaryService.services.alerts;

import com.example.AviaryService.entity.AlertPreference;

// How far ahead an item counts as "due soon": the owner's alert lead times.
// The alert email, the PDF and the dashboard's amber pills all use these, so
// they always agree (spec 013).
public record LeadTimes(int days, int hours) {

    // Same defaults as AlertPreference: 30 days, 10 hours.
    public static final LeadTimes DEFAULT = new LeadTimes(30, 10);

    public static LeadTimes of(AlertPreference prefs) {
        return prefs == null ? DEFAULT : new LeadTimes(prefs.getLeadTimeDays(), prefs.getLeadTimeHours());
    }
}
