package com.example.AviaryService.services;

// The "send an email" interface the rest of the app uses. Today the only
// version is SendGridEmailService. To switch email providers, write a new
// version of this; nothing else has to change.
// See docs/ALERTS_SPEC.md and docs/SHARE_EXPORT_SPEC.md.
public interface EmailSender {

    // False when no API key / from-address is configured. Callers should check
    // this and degrade gracefully rather than let a send throw.
    boolean isConfigured();

    // Plain-text email, no attachment (confirmation links, reminders).
    void send(String toEmail, String subject, String body);

    // Plain-text email with a single PDF attachment (dashboard export, digests).
    void sendPdf(String toEmail, String subject, String body, byte[] pdfBytes, String filename);
}
