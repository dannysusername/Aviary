package com.example.AviaryService.services.alerts;

import java.time.ZonedDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.AviaryService.entity.AlertPreference;
import com.example.AviaryService.repositories.AlertPreferenceRepository;

// The clock for maintenance alerts. Runs every hour and checks each user whose
// chosen alert hour matches, in that user's own time zone. AlertService makes sure nobody is checked more
// than once a day. Runs at :05 so it doesn't overlap FlightSyncService, which
// runs at :00. See docs/specs/ALERTS_SPEC.md.
@Component
public class AlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(AlertScheduler.class);

    private final AlertPreferenceRepository preferenceRepository;
    private final AlertService alertService;

    public AlertScheduler(AlertPreferenceRepository preferenceRepository, AlertService alertService) {
        this.preferenceRepository = preferenceRepository;
        this.alertService = alertService;
    }

    @Scheduled(cron = "0 5 * * * *")
    public void tick() {
        List<AlertPreference> enabled = preferenceRepository.findByAlertsEnabledTrue();
        for (AlertPreference prefs : enabled) {
            try {
                // Each owner's own hour and date (spec 014): 7:00 is their 7:00.
                ZonedDateTime local = alertService.nowFor(prefs);
                alertService.runSweepForUser(prefs.getUser(), prefs, local.toLocalDate(), local.getHour());
            } catch (Exception e) {
                // One user's bad data must never stall the whole sweep.
                log.warn("Alert sweep failed for user {}: {}",
                    prefs.getUser() != null ? prefs.getUser().getUsername() : "?", e.getMessage());
            }
        }

        try {
            alertService.housekeepPendingRecipients();
        } catch (Exception e) {
            log.warn("Recipient housekeeping failed: {}", e.getMessage());
        }
        try {
            alertService.pruneSendLog();
        } catch (Exception e) {
            log.warn("Send-log prune failed: {}", e.getMessage());
        }
    }
}
