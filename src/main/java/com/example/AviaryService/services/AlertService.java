package com.example.AviaryService.services;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.AlertPreference;
import com.example.AviaryService.entity.AlertRecipient;
import com.example.AviaryService.entity.AlertRecipient.Status;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AlertPreferenceRepository;
import com.example.AviaryService.repositories.AlertRecipientRepository;
import com.example.AviaryService.repositories.AlertSendLogRepository;

// The main alerts logic. Saves alert settings, handles "Send alert now", runs
// the daily check for each user, and does cleanup (reminds or expires
// recipients who never confirmed, deletes old send logs).
// AlertController and AlertScheduler both call into this. See docs/ALERTS_SPEC.md.
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final int SEND_LOG_RETENTION_DAYS = 30;

    private final AlertPreferenceRepository preferenceRepository;
    private final AlertRecipientRepository recipientRepository;
    private final AlertSendLogRepository sendLogRepository;
    private final AlertDigestService digestService;
    private final NotificationService notificationService;

    public AlertService(AlertPreferenceRepository preferenceRepository,
            AlertRecipientRepository recipientRepository,
            AlertSendLogRepository sendLogRepository,
            AlertDigestService digestService,
            NotificationService notificationService) {
        this.preferenceRepository = preferenceRepository;
        this.recipientRepository = recipientRepository;
        this.sendLogRepository = sendLogRepository;
        this.digestService = digestService;
        this.notificationService = notificationService;
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
            int leadTimeDays, int leadTimeHours, int overdueRenudgeDays) {
        AlertPreference prefs = getOrCreatePrefs(user);
        boolean wasEnabled = prefs.isAlertsEnabled();

        prefs.setCheckHour(clamp(checkHour, 0, 23));
        prefs.setLeadTimeDays(Math.max(0, leadTimeDays));
        prefs.setLeadTimeHours(Math.max(0, leadTimeHours));
        prefs.setOverdueRenudgeDays(Math.max(1, overdueRenudgeDays));
        prefs.setAlertsEnabled(enabled);
        preferenceRepository.save(prefs);

        if (enabled && !wasEnabled) {
            sendBaseline(user, prefs);
        }
        return prefs;
    }

    private void sendBaseline(User user, AlertPreference prefs) {
        AlertDigest digest = digestService.buildDigest(user, prefs, true);
        int sent = notificationService.sendDigest(user, digest);
        digestService.markFired(user, prefs);
        prefs.setBaselineSentAt(Instant.now());
        prefs.setLastCheckedOn(LocalDate.now(ZoneOffset.UTC));
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
        return sent;
    }

    // One user's daily evaluation. No-op unless alerts are on, it's their chosen
    // hour, and today's sweep hasn't already run.
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
        boolean sentSomething = false;
        if (digest.shouldSend()) {
            int sent = notificationService.sendDigest(user, digest);
            digestService.markFired(user, prefs);
            sentSomething = sent > 0;
            log.info("Daily alert sweep for {}: digest sent to {} recipient(s).", user.getUsername(), sent);
        }
        prefs.setLastCheckedOn(today);
        preferenceRepository.save(prefs);
        return sentSomething;
    }

    // Called once per scheduler tick, after the per-user sweeps.
    @Transactional
    public void housekeepPendingRecipients() {
        Instant now = Instant.now();
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
        sendLogRepository.deleteBySentAtBefore(Instant.now().minus(Duration.ofDays(SEND_LOG_RETENTION_DAYS)));
    }

    private void sendReminder(AlertRecipient r) {
        String base = notificationService.baseUrl();
        String tail = r.getUser().getTailNumber() == null || r.getUser().getTailNumber().isBlank()
            ? "an aircraft" : r.getUser().getTailNumber();
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

    // Used by the scheduler to know "what hour is it" consistently.
    public int currentLocalHour() {
        return ZonedDateTime.now(ZoneId.systemDefault()).getHour();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
