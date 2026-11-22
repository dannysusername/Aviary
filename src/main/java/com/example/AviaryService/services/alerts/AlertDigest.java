package com.example.AviaryService.services.alerts;

import java.time.Instant;
import java.util.List;

// One alert message's content: whether there's anything to send, the subject,
// and what's due per aircraft. Only holds data: AlertDigestService builds it,
// AlertEmailRenderer turns it into the text and HTML emails.
// See docs/specs/ALERTS_SPEC.md and specs/013-alert-email-design/data-model.md.
public class AlertDigest {

    // One aircraft's part of the email. `due` is overdue items first, then
    // due soon, each group sorted by sortKey (most urgent first).
    public record AircraftSection(String tail, String makeModel, String hobbsTime, String timeInService,
                                  List<DueItem> due, int onTrackCount, int itemCount) {
        public boolean hasDue() {
            return !due.isEmpty();
        }
    }

    // An item that needs attention. timeText is e.g. "10 days left" or
    // "480.0 hrs overdue · 106 days left". isNew: worse than at the last email.
    public record DueItem(String name, AlertLevel level, String timeText, boolean isNew, double sortKey) {
        public boolean overdue() {
            return level == AlertLevel.OVERDUE;
        }
    }

    private final boolean shouldSend;
    private String subject;
    private final int overdueCount;
    private final int dueSoonCount;
    private final Instant asOf;
    private final boolean baseline;
    private final List<AircraftSection> sections;
    private final LeadTimes leadTimes;
    private String body = "";
    // Spec 014: what the schedule needs. newX = an item got worse into that
    // state since the last email.
    private boolean newDueSoon;
    private boolean newOverdue;
    private java.time.ZoneId zone = com.example.AviaryService.entity.AlertPreference.DEFAULT_ZONE;

    public AlertDigest(boolean shouldSend, String subject, int overdueCount, int dueSoonCount,
                       Instant asOf, boolean baseline, List<AircraftSection> sections, LeadTimes leadTimes) {
        this.shouldSend = shouldSend;
        this.subject = subject;
        this.overdueCount = overdueCount;
        this.dueSoonCount = dueSoonCount;
        this.asOf = asOf;
        this.baseline = baseline;
        this.sections = List.copyOf(sections);
        this.leadTimes = leadTimes;
    }

    public boolean shouldSend() { return shouldSend; }
    public String subject() { return subject; }
    public int overdueCount() { return overdueCount; }
    public int dueSoonCount() { return dueSoonCount; }
    public Instant asOf() { return asOf; }
    public boolean baseline() { return baseline; }
    public List<AircraftSection> sections() { return sections; }
    public LeadTimes leadTimes() { return leadTimes; }

    // The plain-text email without the per-recipient footer.
    public String body() { return body; }

    void setBody(String body) { this.body = body; }

    public boolean newDueSoon() { return newDueSoon; }
    public boolean newOverdue() { return newOverdue; }
    public java.time.ZoneId zone() { return zone; }

    void setFacts(boolean newDueSoon, boolean newOverdue, java.time.ZoneId zone) {
        this.newDueSoon = newDueSoon;
        this.newOverdue = newOverdue;
        if (zone != null) this.zone = zone;
    }

    void setSubject(String subject) { this.subject = subject; }

    public boolean isFleet() {
        return sections.size() > 1;
    }
}
