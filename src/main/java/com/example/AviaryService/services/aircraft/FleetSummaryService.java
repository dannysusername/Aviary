package com.example.AviaryService.services.aircraft;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.AviaryService.entity.AlertPreference;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AlertPreferenceRepository;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.services.alerts.AlertLevel;
import com.example.AviaryService.util.DueDates;

// Overdue / due-soon counts per aircraft, for the aircraft picker. Uses the
// same DueDates.classify and the same lead times as the alert digest, so the
// picker and the alert email never disagree about what is "due soon".
@Service
public class FleetSummaryService {

    // Defaults match AlertPreference's own field defaults.
    static final int DEFAULT_LEAD_DAYS = 30;
    static final int DEFAULT_LEAD_HOURS = 10;

    public record Counts(int overdue, int dueSoon) {
        public static final Counts NONE = new Counts(0, 0);
    }

    private final ServiceTimelineRepository timelineRepository;
    private final AlertPreferenceRepository preferenceRepository;

    public FleetSummaryService(ServiceTimelineRepository timelineRepository,
            AlertPreferenceRepository preferenceRepository) {
        this.timelineRepository = timelineRepository;
        this.preferenceRepository = preferenceRepository;
    }

    // aircraft id -> counts. Aircraft with no rows are absent (treat as NONE).
    public Map<Long, Counts> summarize(User user, LocalDate today) {
        AlertPreference prefs = preferenceRepository.findByUser(user).orElse(null);
        int leadDays = prefs == null ? DEFAULT_LEAD_DAYS : prefs.getLeadTimeDays();
        int leadHours = prefs == null ? DEFAULT_LEAD_HOURS : prefs.getLeadTimeHours();

        List<ServiceTimeline> rows = timelineRepository.findByUserOrderByAircraftIdAscTimelineOrderAsc(user);
        Map<Long, int[]> tally = new HashMap<>();
        for (ServiceTimeline t : rows) {
            if (t.getIsTitle() || t.getAircraft() == null) continue;
            // Each row against its own aircraft's time in service.
            AlertLevel level = DueDates.classify(t.getDueDateDate(), t.getDueDateHours(),
                t.getAircraft().getTimeInServiceHours(), today, leadDays, leadHours);
            int[] c = tally.computeIfAbsent(t.getAircraft().getId(), k -> new int[2]);
            if (level == AlertLevel.OVERDUE) c[0]++;
            else if (level == AlertLevel.DUE_SOON) c[1]++;
        }
        Map<Long, Counts> out = new HashMap<>();
        tally.forEach((id, c) -> out.put(id, new Counts(c[0], c[1])));
        return out;
    }
}
