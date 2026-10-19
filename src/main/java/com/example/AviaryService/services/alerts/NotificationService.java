package com.example.AviaryService.services.alerts;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.AviaryService.entity.AlertRecipient;
import com.example.AviaryService.entity.AlertSendLog;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AlertRecipientRepository;
import com.example.AviaryService.repositories.AlertSendLogRepository;
import com.example.AviaryService.services.dashboard.PdfExportService;
import com.example.AviaryService.services.messaging.EmailSender;
import com.example.AviaryService.services.messaging.SmsSender;

// Sends an alert to each of a user's confirmed recipients, one message each.
// Limit per recipient: one email per 24 hours, one text per 72 hours.
// "Send alert now" and the daily check share that limit. A recipient on
// cooldown, or a sender that isn't set up, is skipped and logged instead of
// causing an error. See docs/specs/ALERTS_SPEC.md ("Rate limit").
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final Duration EMAIL_COOLDOWN = Duration.ofHours(24);
    private static final Duration SMS_COOLDOWN = Duration.ofHours(72);

    private final EmailSender emailSender;
    private final SmsSender smsSender;
    private final AlertRecipientRepository recipientRepository;
    private final AlertSendLogRepository sendLogRepository;
    private final PdfExportService pdfExportService;

    private final String baseUrl;
    private final String postalAddress;

    public NotificationService(EmailSender emailSender, SmsSender smsSender,
            AlertRecipientRepository recipientRepository, AlertSendLogRepository sendLogRepository,
            PdfExportService pdfExportService,
            @Value("${aviary.alerts.base-url:}") String baseUrl,
            @Value("${aviary.alerts.postal-address:}") String postalAddress) {
        this.emailSender = emailSender;
        this.smsSender = smsSender;
        this.recipientRepository = recipientRepository;
        this.sendLogRepository = sendLogRepository;
        this.pdfExportService = pdfExportService;
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

        byte[] pdf = null; // built lazily, once, only if an email actually goes out
        int sent = 0;

        for (AlertRecipient r : accepted) {
            if (onCooldown(r)) {
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
                        pdf = pdfExportService.generateDashboardPdf(user);
                    }
                    emailSender.sendPdf(r.getDestination(), digest.subject(),
                        digest.body() + emailFooter(user, r),
                        pdf, "Aviary_Maintenance_" + java.time.LocalDate.now() + ".pdf");
                } else {
                    smsSender.send(r.getDestination(), smsBody(user, digest));
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

    private boolean onCooldown(AlertRecipient r) {
        Duration cd = r.getChannelEnum() == AlertRecipient.Channel.SMS ? SMS_COOLDOWN : EMAIL_COOLDOWN;
        return sendLogRepository.existsByRecipientIdAndSentAtAfter(r.getId(), Instant.now().minus(cd));
    }

    private void recordSend(AlertRecipient r) {
        sendLogRepository.save(new AlertSendLog(r.getId(), r.getChannelEnum()));
    }

    private String emailFooter(User user, AlertRecipient r) {
        StringBuilder sb = new StringBuilder("\n\n----\n");
        sb.append("You're receiving this because ").append(AlertText.owner(user))
            .append(" added you to maintenance\nalerts for ").append(AlertText.tail(user))
            .append(". The full dashboard is attached as a PDF.\n");
        if (!baseUrl.isBlank()) {
            sb.append("Stop these emails: ").append(baseUrl)
              .append("/alerts/unsubscribe?token=").append(r.getConfirmToken()).append('\n');
        }
        if (!postalAddress.isBlank()) {
            sb.append(postalAddress).append('\n');
        }
        return sb.toString();
    }

    private String smsBody(User user, AlertDigest digest) {
        StringBuilder sb = new StringBuilder(orAircraft(user.getTailNumber())).append(": ");
        sb.append(digest.overdueCount()).append(" overdue, ")
          .append(digest.dueSoonCount()).append(" due soon.");
        if (!baseUrl.isBlank()) {
            sb.append(' ').append(baseUrl).append("/dashboard");
        }
        sb.append(" Reply STOP to unsubscribe.");
        return sb.toString();
    }

    private static String orAircraft(String tail) {
        return tail == null || tail.isBlank() ? "your aircraft" : tail;
    }
}
