package com.example.AviaryService.services.alerts;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.AlertRecipient;
import com.example.AviaryService.entity.AlertSendLog;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.AlertRecipientRepository;
import com.example.AviaryService.repositories.AlertSendLogRepository;
import com.example.AviaryService.services.dashboard.PdfExportService;
import com.example.AviaryService.services.messaging.EmailSender;
import com.example.AviaryService.services.messaging.SmsSender;

// Sends an alert to each of a user's confirmed recipients, one message each.
// Limit per recipient: one email per calendar day (owner's zone), one text per 72 hours.
// "Send alert now" and the daily check share that limit. A recipient on
// cooldown, or a sender that isn't set up, is skipped and logged instead of
// causing an error. See docs/specs/ALERTS_SPEC.md ("Rate limit").
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final Duration SMS_COOLDOWN = Duration.ofHours(72);

    private final EmailSender emailSender;
    private final SmsSender smsSender;
    private final AlertRecipientRepository recipientRepository;
    private final AlertSendLogRepository sendLogRepository;
    private final PdfExportService pdfExportService;
    private final AircraftRepository aircraftRepository;
    private final AlertEmailRenderer renderer;
    private final java.time.Clock clock;

    private final String baseUrl;
    private final String postalAddress;

    public NotificationService(EmailSender emailSender, SmsSender smsSender,
            AlertRecipientRepository recipientRepository, AlertSendLogRepository sendLogRepository,
            PdfExportService pdfExportService, AircraftRepository aircraftRepository,
            AlertEmailRenderer renderer, java.time.Clock clock,
            @Value("${aviary.alerts.base-url:}") String baseUrl,
            @Value("${aviary.alerts.postal-address:}") String postalAddress) {
        this.emailSender = emailSender;
        this.smsSender = smsSender;
        this.recipientRepository = recipientRepository;
        this.sendLogRepository = sendLogRepository;
        this.pdfExportService = pdfExportService;
        this.aircraftRepository = aircraftRepository;
        this.renderer = renderer;
        this.clock = clock;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.postalAddress = postalAddress == null ? "" : postalAddress;
    }

    // Sends the digest to every ACCEPTED recipient not on cooldown. Returns how
    // many messages were actually handed to a sender.
    public int sendDigest(User user, AlertDigest digest) {
        List<AlertRecipient> accepted =
            recipientRepository.findByUserAndStatus(user, AlertRecipient.Status.ACCEPTED.name());
        if (accepted.isEmpty()) {
            log.info("Alert digest for {} has no accepted recipients -- nothing sent.", user.getUsername());
            return 0;
        }

        List<Aircraft> fleet = aircraftRepository.findByUserOrderByIdAsc(user);
        byte[] pdf = null; // built lazily, once, only if an email actually goes out
        int sent = 0;

        for (AlertRecipient r : accepted) {
            if (onCooldown(r, digest.zone())) {
                log.info("Recipient {} on cooldown -- skipping this digest.", r.getId());
                continue;
            }
            try {
                if (r.getChannelEnum() == AlertRecipient.Channel.EMAIL) {
                    if (!emailSender.isConfigured()) {
                        log.warn("Email sender not configured -- skipping recipient {}.", r.getId());
                        continue;
                    }
                    if (pdf == null) {
                        pdf = pdfExportService.generateFleetPdf(fleet, java.time.LocalDate.now(clock.withZone(digest.zone())), digest.leadTimes());
                    }
                    EmailFooter footer = footerFor(fleet, r);
                    emailSender.sendPdf(r.getDestination(), digest.subject(),
                        renderer.text(digest, footer), renderer.html(digest, footer),
                        pdf, "Aviary_Maintenance_" + java.time.LocalDate.now(clock.withZone(digest.zone())) + ".pdf");
                } else {
                    smsSender.send(r.getDestination(), smsBody(fleet, digest));
                }
                recordSend(r);
                sent++;
            } catch (RuntimeException e) {
                log.error("Failed to send alert to recipient {} ({})", r.getId(), r.getDestination(), e);
            }
        }
        return sent;
    }

    // Sends a plain one-off message to a single email recipient (confirmation
    // links, reminders). Bypasses the digest rate limit -- these are
    // transactional and self-limiting. No-ops with a log if email is unconfigured.
    public void sendPlainEmail(String toEmail, String subject, String body) {
        if (!emailSender.isConfigured()) {
            log.warn("Email sender not configured -- not sending '{}' to {}.", subject, toEmail);
            return;
        }
        emailSender.send(toEmail, subject, body);
    }

    public void sendPlainSms(String toPhone, String body) {
        smsSender.send(toPhone, body);
    }

    public String baseUrl() {
        return baseUrl;
    }

    // -- internals --

    // Email: at most one per calendar day in the owner's zone (spec 014 R4).
    // A rolling 24 hours dropped tomorrow's email whenever the hourly check
    // ran a few seconds earlier than today's. Text: one per 72 hours.
    private boolean onCooldown(AlertRecipient r, java.time.ZoneId zone) {
        Instant since = r.getChannelEnum() == AlertRecipient.Channel.SMS
            ? Instant.now(clock).minus(SMS_COOLDOWN)
            : java.time.LocalDate.now(clock.withZone(zone)).atStartOfDay(zone).toInstant().minusNanos(1);
        return sendLogRepository.existsByRecipientIdAndSentAtAfter(r.getId(), since);
    }

    private void recordSend(AlertRecipient r) {
        AlertSendLog entry = new AlertSendLog(r.getId(), r.getChannelEnum());
        entry.setSentAt(Instant.now(clock));
        sendLogRepository.save(entry);
    }

    // Per recipient: their own stop link (spec 013 research R2).
    private EmailFooter footerFor(List<Aircraft> fleet, AlertRecipient r) {
        String stop = baseUrl.isBlank() ? null : baseUrl + "/alerts/unsubscribe?token=" + r.getConfirmToken();
        return new EmailFooter(AlertText.operator(fleet), AlertText.tail(fleet), stop, postalAddress);
    }

    private String smsBody(List<Aircraft> fleet, AlertDigest digest) {
        StringBuilder sb = new StringBuilder(AlertText.tail(fleet)).append(": ");
        sb.append(digest.overdueCount()).append(" overdue, ")
          .append(digest.dueSoonCount()).append(" due soon.");
        if (!baseUrl.isBlank()) {
            sb.append(' ').append(baseUrl).append("/dashboard");
        }
        sb.append(" Reply STOP to unsubscribe.");
        return sb.toString();
    }

}
