package com.example.AviaryService.services.alerts;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.AlertPreference;
import com.example.AviaryService.entity.AlertRecipient;
import com.example.AviaryService.entity.AlertRecipient.Status;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.AlertPreferenceRepository;
import com.example.AviaryService.repositories.AlertRecipientRepository;
import com.example.AviaryService.repositories.AlertSendLogRepository;

// The main alerts logic. Saves alert settings, handles "Send alert now", runs
// the daily check for each user, and does cleanup (reminds or expires
// recipients who never confirmed, deletes old send logs).
// AlertController and AlertScheduler both call into this. See docs/specs/ALERTS_SPEC.md.
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final int SEND_LOG_RETENTION_DAYS = 30;

    private final AlertPreferenceRepository preferenceRepository;
    private final AlertRecipientRepository recipientRepository;
    private final AlertSendLogRepository sendLogRepository;
    private final AlertDigestService digestService;
    private final NotificationService notificationService;
    private final AircraftRepository aircraftRepository;
    private final Clock clock;

    public AlertService(AlertPreferenceRepository preferenceRepository,
            AlertRecipientRepository recipientRepository,
            AlertSendLogRepository sendLogRepository,
            AlertDigestService digestService,
            NotificationService notificationService, AircraftRepository aircraftRepository, Clock clock) {
        this.preferenceRepository = preferenceRepository;
        this.recipientRepository = recipientRepository;
        this.sendLogRepository = sendLogRepository;
        this.digestService = digestService;
        this.notificationService = notificationService;
        this.aircraftRepository = aircraftRepository;
        this.clock = clock;
    }

    @Transactional
    public AlertPreference getOrCreatePrefs(User user) {
        return preferenceRepository.findByUser(user)
            .orElseGet(() -> preferenceRepository.save(new AlertPreference(user)));
    }

    // Applies a settings change. Flipping alertsEnabled false -> true sends the
    // one-time baseline digest and snapshots every item's level so the user
    // isn't flooded on the next sweep.
    @Transactional
    public AlertPreference updatePrefs(User user, boolean enabled, int checkHour,
            int leadTimeDays, int leadTimeHours, int overdueEvery) {
        AlertPreference current = getOrCreatePrefs(user);
        return updatePrefs(user, enabled, checkHour, leadTimeDays, leadTimeHours, overdueEvery,
            current.getDueSoonEveryDays(), current.getStatusEveryDays());
    }

    // Frequencies use AlertSchedule.Frequency's encoding (0 Off, -1 Once,
    // 1-90 days); the controller has already parsed and validated them.
    @Transactional
    public AlertPreference updatePrefs(User user, boolean enabled, int checkHour,
            int leadTimeDays, int leadTimeHours, int overdueEvery, int dueSoonEvery, int statusEvery) {
        AlertPreference prefs = getOrCreatePrefs(user);
        boolean wasEnabled = prefs.isAlertsEnabled();

        prefs.setCheckHour(clamp(checkHour, 0, 23));
        prefs.setLeadTimeDays(Math.max(0, leadTimeDays));
        prefs.setLeadTimeHours(Math.max(0, leadTimeHours));
        prefs.setOverdueEveryDays(frequency(overdueEvery, true));
        prefs.setDueSoonEveryDays(frequency(dueSoonEvery, true));
        prefs.setStatusEveryDays(frequency(statusEvery, false));
        prefs.setAlertsEnabled(enabled);
        preferenceRepository.save(prefs);

        if (enabled && !wasEnabled) {
            sendBaseline(user, prefs);
        }
        return prefs;
    }

    // The owner's time zone, from their browser. Invalid ids are refused.
    @Transactional
    public AlertPreference updateTimeZone(User user, String zoneId) {
        ZoneId zone = ZoneId.of(zoneId.trim()); // DateTimeException if invalid
        AlertPreference prefs = getOrCreatePrefs(user);
        prefs.setTimeZone(zone.getId());
        return preferenceRepository.save(prefs);
    }

    private void sendBaseline(User user, AlertPreference prefs) {
        AlertDigest digest = digestService.buildDigest(user, prefs, true);
        int sent = notificationService.sendDigest(user, digest);
        digestService.markFired(user, prefs);
        // The baseline is a full status, so every timer starts from it.
        recordSent(prefs, digest, true);
        prefs.setBaselineSentAt(Instant.now(clock));
        prefs.setLastCheckedOn(LocalDate.now(clock.withZone(prefs.zone())));
        preferenceRepository.save(prefs);
        log.info("Baseline alert digest for {} sent to {} recipient(s).", user.getUsername(), sent);
    }

    // Manual "Send alert now" -- pushes the current status regardless of whether
    // anything changed. Per-recipient rate limits still apply inside sendDigest.
    @Transactional
    public int sendNow(User user) {
        AlertPreference prefs = getOrCreatePrefs(user);
        AlertDigest digest = digestService.buildDigest(user, prefs, false);
        int sent = notificationService.sendDigest(user, digest);
        digestService.markFired(user, prefs);
        recordSent(prefs, digest, false);
        preferenceRepository.save(prefs);
        return sent;
    }

    // One user's daily evaluation. No-op unless alerts are on, it's their chosen
    // hour, and today's sweep hasn't already run. `today` and `currentHour`
    // are in the owner's zone (AlertScheduler). Whether to email is
    // AlertSchedule's call (spec 014).
    @Transactional
    public boolean runSweepForUser(User user, AlertPreference prefs, LocalDate today, int currentHour) {
        if (!prefs.isAlertsEnabled()) {
            return false;
        }
        if (prefs.getCheckHour() != currentHour) {
            return false;
        }
        if (today.equals(prefs.getLastCheckedOn())) {
            return false;
        }

        AlertDigest digest = digestService.buildDigest(user, prefs, false);
        ZoneId zone = prefs.zone();
        if (prefs.getLastOverdueSentAt() == null && digest.overdueCount() > 0) {
            // First run after spec 014: continue the old per-item re-nudge clock.
            prefs.setLastOverdueSentAt(digestService.latestOverdueAlertAt(user));
        }
        Set<AlertSchedule.Reason> reasons = AlertSchedule.decide(new AlertSchedule.Input(
            prefs.getDueSoonEveryDays(), prefs.getOverdueEveryDays(), prefs.getStatusEveryDays(),
            digest.dueSoonCount() > 0, digest.overdueCount() > 0, digest.newDueSoon(), digest.newOverdue(),
            today, localDate(prefs.getLastDueSoonSentAt(), zone), localDate(prefs.getLastOverdueSentAt(), zone),
            localDate(prefs.getLastStatusSentAt(), zone)));

        boolean sentSomething = false;
        if (!reasons.isEmpty()) {
            if (reasons.equals(Set.of(AlertSchedule.Reason.STATUS_REPORT))) {
                digestService.markAsStatusReport(digest, aircraftRepository.findByUserOrderByIdAsc(user),
                    prefs.getStatusEveryDays());
            }
            int sent = notificationService.sendDigest(user, digest);
            digestService.markFired(user, prefs);
            recordSent(prefs, digest, reasons.contains(AlertSchedule.Reason.STATUS_REPORT));
            sentSomething = sent > 0;
            log.info("Daily alert sweep for {} ({}): digest sent to {} recipient(s).", user.getUsername(), reasons, sent);
        }
        prefs.setLastCheckedOn(today);
        preferenceRepository.save(prefs);
        return sentSomething;
    }

    // After an email goes out: each kind of reminder counts from the last
    // email that listed its items (research R7).
    private void recordSent(AlertPreference prefs, AlertDigest digest, boolean statusReport) {
        Instant now = Instant.now(clock);
        if (digest.dueSoonCount() > 0) prefs.setLastDueSoonSentAt(now);
        if (digest.overdueCount() > 0) prefs.setLastOverdueSentAt(now);
        if (statusReport) prefs.setLastStatusSentAt(now);
    }

    private static LocalDate localDate(Instant at, ZoneId zone) {
        return at == null ? null : at.atZone(zone).toLocalDate();
    }

    private static int frequency(int v, boolean allowOnce) {
        if (v == AlertSchedule.Frequency.OFF) return v;
        if (v == AlertSchedule.Frequency.ONCE) {
            if (!allowOnce) throw new IllegalArgumentException("The status report can be Off or every N days.");
            return v;
        }
        return clamp(v, 1, AlertSchedule.Frequency.MAX_DAYS);
    }

    // Called once per scheduler tick, after the per-user sweeps.
    @Transactional
    public void housekeepPendingRecipients() {
        Instant now = Instant.now(clock);
        List<AlertRecipient> pending = recipientRepository.findByStatus(Status.PENDING.name());
        for (AlertRecipient r : pending) {
            long ageDays = Duration.between(r.getCreatedAt(), now).toDays();
            if (ageDays >= AlertRecipient.CONFIRM_EXPIRY_DAYS) {
                r.setStatusEnum(Status.EXPIRED);
                recipientRepository.save(r);
                log.info("Recipient {} confirmation expired.", r.getId());
            } else if (ageDays >= AlertRecipient.CONFIRM_REMINDER_DAYS && r.getReminderSentAt() == null) {
                sendReminder(r);
                r.setReminderSentAt(now);
                recipientRepository.save(r);
            }
        }
    }

    @Transactional
    public void pruneSendLog() {
        sendLogRepository.deleteBySentAtBefore(Instant.now(clock).minus(Duration.ofDays(SEND_LOG_RETENTION_DAYS)));
    }

    private void sendReminder(AlertRecipient r) {
        String base = notificationService.baseUrl();
        List<Aircraft> fleet = aircraftRepository.findByUserOrderByIdAsc(r.getUser());
        String tail = fleet.size() == 1 ? AlertText.tailOf(fleet.get(0), "an aircraft") : AlertText.tail(fleet);
        String confirmUrl = base.isBlank() ? "" : base + "/alerts/confirm?token=" + r.getConfirmToken();
        if (r.getChannelEnum() == AlertRecipient.Channel.EMAIL) {
            notificationService.sendPlainEmail(r.getDestination(),
                "Reminder: confirm maintenance alerts for " + tail,
                "You were added to receive maintenance alerts for " + tail + " but haven't confirmed yet.\n"
                + (confirmUrl.isEmpty() ? "" : "Confirm: " + confirmUrl + "\n")
                + "\nThis request expires "
                + (AlertRecipient.CONFIRM_EXPIRY_DAYS - AlertRecipient.CONFIRM_REMINDER_DAYS)
                + " days from now.\n");
        } else {
            notificationService.sendPlainSms(r.getDestination(),
                "Reminder: reply YES to get maintenance alerts for " + tail + ", or STOP to decline.");
        }
    }

    // "Now" in the owner's zone; the scheduler compares its hour and date.
    public ZonedDateTime nowFor(AlertPreference prefs) {
        return ZonedDateTime.now(clock).withZoneSameInstant(prefs.zone());
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
