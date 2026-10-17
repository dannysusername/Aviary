package com.example.AviaryService.services;

// One alert message's content: whether there's anything to send, plus the
// subject and body. Only holds data; AlertDigestService builds it.
// See docs/ALERTS_SPEC.md.
public class AlertDigest {

    private final boolean shouldSend;
    private final String subject;
    private final String body;
    private final int overdueCount;
    private final int dueSoonCount;

    public AlertDigest(boolean shouldSend, String subject, String body, int overdueCount, int dueSoonCount) {
        this.shouldSend = shouldSend;
        this.subject = subject;
        this.body = body;
        this.overdueCount = overdueCount;
        this.dueSoonCount = dueSoonCount;
    }

    public boolean shouldSend() {
        return shouldSend;
    }

    public String subject() {
        return subject;
    }

    public String body() {
        return body;
    }

    public int overdueCount() {
        return overdueCount;
    }

    public int dueSoonCount() {
        return dueSoonCount;
    }
}
