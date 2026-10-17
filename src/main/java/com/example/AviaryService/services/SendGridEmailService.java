package com.example.AviaryService.services;

import java.io.IOException;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Attachments;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;

// Sends emails through SendGrid. Only this file uses SendGrid's code.
// See docs/SHARE_EXPORT_SPEC.md and docs/ALERTS_SPEC.md.
//
// Setup: the API key comes from sendgrid.env (not in git) through
// aviary.sendgrid.api-key. The from-email must be verified in SendGrid, or
// every send fails with a 403. If the key or from-email is blank,
// isConfigured() returns false and sending throws, so check it first.
@Service
public class SendGridEmailService implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SendGridEmailService.class);

    private final SendGrid sendGrid;
    private final String fromEmail;
    private final String fromName;
    private final boolean configured;

    public SendGridEmailService(
            @Value("${aviary.sendgrid.api-key:}") String apiKey,
            @Value("${aviary.sendgrid.from-email:}") String fromEmail,
            @Value("${aviary.sendgrid.from-name:Aviary Service}") String fromName) {
        this.sendGrid = new SendGrid(apiKey);
        this.fromEmail = fromEmail;
        this.fromName = fromName;
        this.configured = !apiKey.isBlank() && !fromEmail.isBlank();
        if (!configured) {
            log.warn("SendGrid not configured (aviary.sendgrid.api-key / from-email missing) -- email sending is disabled.");
        }
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public void send(String toEmail, String subject, String body) {
        dispatch(buildMail(toEmail, subject, body), toEmail);
    }

    @Override
    public void sendPdf(String toEmail, String subject, String body, byte[] pdfBytes, String filename) {
        Mail mail = buildMail(toEmail, subject, body);

        Attachments attachment = new Attachments();
        attachment.setContent(Base64.getEncoder().encodeToString(pdfBytes));
        attachment.setType("application/pdf");
        attachment.setFilename(filename);
        attachment.setDisposition("attachment");
        mail.addAttachments(attachment);

        dispatch(mail, toEmail);
    }

    private Mail buildMail(String toEmail, String subject, String body) {
        if (!configured) {
            throw new IllegalStateException("SendGrid is not configured; cannot send email.");
        }
        return new Mail(
            new Email(fromEmail, fromName),
            subject,
            new Email(toEmail),
            new Content("text/plain", body));
    }

    // Fires the request. SendGrid returns 202 Accepted on success; anything
    // outside 2xx is thrown so the caller can log/surface it.
    private void dispatch(Mail mail, String toEmail) {
        try {
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sendGrid.api(request);
            int status = response.getStatusCode();
            if (status < 200 || status >= 300) {
                throw new RuntimeException("SendGrid rejected the message (HTTP " + status + "): " + response.getBody());
            }
            log.info("SendGrid accepted message to {} (HTTP {})", toEmail, status);
        } catch (IOException e) {
            throw new RuntimeException("Failed to reach SendGrid: " + e.getMessage(), e);
        }
    }
}
