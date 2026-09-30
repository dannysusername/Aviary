package com.example.AviaryService.services;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.AlertRecipient;
import com.example.AviaryService.entity.AlertRecipient.Channel;
import com.example.AviaryService.entity.AlertRecipient.Status;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AlertRecipientRepository;

// Add / list / remove / resend maintenance-alert recipients, and the
// token-guarded confirm / decline / unsubscribe transitions the emailed links
// hit. A recipient is PENDING until they themselves confirm -- user A cannot
// consent for user B (docs/ALERTS_SPEC.md "Recipients").
@Service
public class AlertRecipientService {

    private static final Logger log = LoggerFactory.getLogger(AlertRecipientService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    // Deliberately loose -- SendGrid/Twilio do the real validation. This just
    // stops obvious junk from ever creating a row.
    private static final String EMAIL_RE = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";
    private static final String PHONE_RE = "^\\+[1-9]\\d{6,14}$";
    private static final int MAX_RECIPIENTS = 10;

    private final AlertRecipientRepository recipientRepository;
    private final NotificationService notificationService;
    private final OutboundEmailLimiter emailLimiter;

    public AlertRecipientService(AlertRecipientRepository recipientRepository,
            NotificationService notificationService, OutboundEmailLimiter emailLimiter) {
        this.recipientRepository = recipientRepository;
        this.notificationService = notificationService;
        this.emailLimiter = emailLimiter;
    }

    public List<AlertRecipient> list(User user) {
        return recipientRepository.findByUserOrderByCreatedAtAsc(user);
    }

    @Transactional
    public AlertRecipient add(User user, Channel channel, String rawDestination, String label) {
        String destination = rawDestination == null ? "" : rawDestination.trim();
        validate(channel, destination);
        com.example.AviaryService.util.Validation.maxLength("Label", label == null ? null : label.trim(), 100);

        Optional<AlertRecipient> existing =
            recipientRepository.findByUserAndChannelAndDestination(user, channel.name(), destination);
        if (existing.isPresent()) {
            AlertRecipient r = existing.get();
            if (r.getStatusEnum() == Status.DECLINED || r.getStatusEnum() == Status.EXPIRED) {
                // Re-inviting someone who previously declined/expired: fresh token, back to PENDING.
                r.setStatusEnum(Status.PENDING);
                r.setConfirmToken(newToken());
                r.setCreatedAt(Instant.now());
                r.setConfirmedAt(null);
                r.setReminderSentAt(null);
                recipientRepository.save(r);
                sendConfirmation(r);
                return r;
            }
            throw new IllegalArgumentException("That address is already on the list (" + r.getStatus().toLowerCase() + ").");
        }

        if (recipientRepository.findByUserOrderByCreatedAtAsc(user).size() >= MAX_RECIPIENTS) {
            throw new IllegalArgumentException("You can have up to " + MAX_RECIPIENTS + " alert recipients.");
        }
        AlertRecipient r = new AlertRecipient(user, channel, destination,
            label == null || label.isBlank() ? null : label.trim(), newToken());
        recipientRepository.save(r);
        sendConfirmation(r);
        return r;
    }

    @Transactional
    public void remove(User user, long id) {
        AlertRecipient r = ownedOrThrow(user, id);
        recipientRepository.delete(r);
    }

    @Transactional
    public AlertRecipient resend(User user, long id) {
        AlertRecipient r = ownedOrThrow(user, id);
        r.setStatusEnum(Status.PENDING);
        r.setConfirmToken(newToken());
        r.setCreatedAt(Instant.now());
        r.setConfirmedAt(null);
        r.setReminderSentAt(null);
        recipientRepository.save(r);
        sendConfirmation(r);
        return r;
    }

    // -- token-guarded public transitions --

    @Transactional
    public boolean confirm(String token) {
        Optional<AlertRecipient> found = recipientRepository.findByConfirmToken(token);
        if (found.isEmpty()) {
            return false;
        }
        AlertRecipient r = found.get();
        if (r.getStatusEnum() == Status.EXPIRED) {
            return false;
        }
        r.setStatusEnum(Status.ACCEPTED);
        r.setConfirmedAt(Instant.now());
        recipientRepository.save(r);
        return true;
    }

    @Transactional
    public boolean decline(String token) {
        Optional<AlertRecipient> found = recipientRepository.findByConfirmToken(token);
        if (found.isEmpty()) {
            return false;
        }
        AlertRecipient r = found.get();
        r.setStatusEnum(Status.DECLINED);
        recipientRepository.save(r);
        return true;
    }

    // -- internals --

    // Counts against the per-user email budget; throwing here rolls back the
    // add/resend that called it, so nothing is saved when the user is limited.
    private void sendConfirmation(AlertRecipient r) {
        emailLimiter.acquire(r.getUser());
        String base = notificationService.baseUrl();
        String confirmUrl = base.isBlank() ? "(link unavailable -- alerts base URL not configured)"
            : base + "/alerts/confirm?token=" + r.getConfirmToken();
        String declineUrl = base.isBlank() ? "" : base + "/alerts/decline?token=" + r.getConfirmToken();
        String tail = r.getUser().getTailNumber() == null || r.getUser().getTailNumber().isBlank()
            ? "an aircraft" : r.getUser().getTailNumber();

        if (r.getChannelEnum() == Channel.EMAIL) {
            String body = ""
                + r.getUser().getUsername() + " added you to receive maintenance alerts for " + tail + ".\n\n"
                + "Confirm you want these: " + confirmUrl + "\n"
                + (declineUrl.isEmpty() ? "" : "Not you / no thanks: " + declineUrl + "\n")
                + "\nThis link expires in " + AlertRecipient.CONFIRM_EXPIRY_DAYS + " days. "
                + "You won't get any alerts until you confirm.\n";
            notificationService.sendPlainEmail(r.getDestination(),
                "Confirm maintenance alerts for " + tail, body);
        } else {
            notificationService.sendPlainSms(r.getDestination(),
                r.getUser().getUsername() + " added you to maintenance alerts for " + tail
                + ". Reply YES to confirm, STOP to decline. " + confirmUrl);
        }
        log.info("Sent {} confirmation to recipient {}", r.getChannel(), r.getId());
    }

    private AlertRecipient ownedOrThrow(User user, long id) {
        AlertRecipient r = recipientRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Recipient not found."));
        if (r.getUser() == null || r.getUser().getId() != user.getId()) {
            throw new IllegalArgumentException("Recipient not found.");
        }
        return r;
    }

    private void validate(Channel channel, String destination) {
        if (destination.isEmpty()) {
            throw new IllegalArgumentException("Enter an address.");
        }
        if (destination.length() > 254) {
            throw new IllegalArgumentException("That address is too long.");
        }
        if (channel == Channel.EMAIL && !destination.matches(EMAIL_RE)) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }
        if (channel == Channel.SMS && !destination.matches(PHONE_RE)) {
            throw new IllegalArgumentException("Enter a phone number in +15551234567 format.");
        }
    }

    static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
