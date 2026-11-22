package com.example.AviaryService.entity;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

// Per-user maintenance-alert settings. See docs/specs/ALERTS_SPEC.md.
// One row per user; created lazily the first time the user opens the alerts
// settings or an alert action needs it.
@Entity
@Table(name = "alert_preferences")
public class AlertPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    // Master switch. Nothing is ever sent while this is false.
    @Column(nullable = false)
    private boolean alertsEnabled = false;

    // Local hour (0-23) the daily sweep evaluates this user. Same idea as
    // Subscription.preferredCheckHour.
    @Column(nullable = false)
    private int checkHour = 7;

    // "Alert me this many days / hours before an item is due."
    @Column(nullable = false)
    private int leadTimeDays = 30;

    @Column(nullable = false)
    private int leadTimeHours = 10;

    // Overdue frequency (spec 014; was "re-nudge overdue every N days", same
    // column): 0 = Off, -1 = Once, 1-90 = every N days. New owners: 3.
    @Column(nullable = false)
    private int overdueRenudgeDays = 3;

    // Due soon frequency: 0 = Off, -1 = Once, 1-90 = every N days.
    @Column(nullable = false, columnDefinition = "integer default 7")
    private int dueSoonEveryDays = 7;

    // Status report frequency: 0 = Off, 1-90 = every N days (no Once).
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int statusEveryDays = 0;

    // Owner's IANA time zone, e.g. America/New_York. Null until the browser
    // reports one; zone() falls back to America/New_York.
    @Column
    private String timeZone;

    // When the last email listing due-soon / overdue items, and the last
    // status report, went out. Each frequency counts from its own.
    @Column
    private Instant lastDueSoonSentAt;

    @Column
    private Instant lastOverdueSentAt;

    @Column
    private Instant lastStatusSentAt;

    // The owner's local date (in zone()) the sweep last evaluated this user -- guards against a double
    // run in the same day if the process restarts on the check hour.
    @Column
    private LocalDate lastCheckedOn;

    // When the one-time "alerts are on" baseline digest went out. Non-null
    // means the false->true transition has already been handled.
    @Column
    private Instant baselineSentAt;

    public AlertPreference() {
    }

    public AlertPreference(User user) {
        this.user = user;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public boolean isAlertsEnabled() {
        return alertsEnabled;
    }

    public void setAlertsEnabled(boolean alertsEnabled) {
        this.alertsEnabled = alertsEnabled;
    }

    public int getCheckHour() {
        return checkHour;
    }

    public void setCheckHour(int checkHour) {
        this.checkHour = checkHour;
    }

    public int getLeadTimeDays() {
        return leadTimeDays;
    }

    public void setLeadTimeDays(int leadTimeDays) {
        this.leadTimeDays = leadTimeDays;
    }

    public int getLeadTimeHours() {
        return leadTimeHours;
    }

    public void setLeadTimeHours(int leadTimeHours) {
        this.leadTimeHours = leadTimeHours;
    }

    public int getOverdueRenudgeDays() {
        return overdueRenudgeDays;
    }

    public void setOverdueRenudgeDays(int overdueRenudgeDays) {
        this.overdueRenudgeDays = overdueRenudgeDays;
    }

    public LocalDate getLastCheckedOn() {
        return lastCheckedOn;
    }

    public void setLastCheckedOn(LocalDate lastCheckedOn) {
        this.lastCheckedOn = lastCheckedOn;
    }

    public Instant getBaselineSentAt() {
        return baselineSentAt;
    }

    public void setBaselineSentAt(Instant baselineSentAt) {
        this.baselineSentAt = baselineSentAt;
    }

    public int getOverdueEveryDays() {
        return overdueRenudgeDays;
    }

    public void setOverdueEveryDays(int v) {
        this.overdueRenudgeDays = v;
    }

    public int getDueSoonEveryDays() {
        return dueSoonEveryDays;
    }

    public void setDueSoonEveryDays(int dueSoonEveryDays) {
        this.dueSoonEveryDays = dueSoonEveryDays;
    }

    public int getStatusEveryDays() {
        return statusEveryDays;
    }

    public void setStatusEveryDays(int statusEveryDays) {
        this.statusEveryDays = statusEveryDays;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public static final java.time.ZoneId DEFAULT_ZONE = java.time.ZoneId.of("America/New_York");

    // The owner's zone, or America/New_York when unknown or invalid.
    public java.time.ZoneId zone() {
        if (timeZone == null || timeZone.isBlank()) return DEFAULT_ZONE;
        try {
            return java.time.ZoneId.of(timeZone);
        } catch (java.time.DateTimeException e) {
            return DEFAULT_ZONE;
        }
    }

    public Instant getLastDueSoonSentAt() {
        return lastDueSoonSentAt;
    }

    public void setLastDueSoonSentAt(Instant lastDueSoonSentAt) {
        this.lastDueSoonSentAt = lastDueSoonSentAt;
    }

    public Instant getLastOverdueSentAt() {
        return lastOverdueSentAt;
    }

    public void setLastOverdueSentAt(Instant lastOverdueSentAt) {
        this.lastOverdueSentAt = lastOverdueSentAt;
    }

    public Instant getLastStatusSentAt() {
        return lastStatusSentAt;
    }

    public void setLastStatusSentAt(Instant lastStatusSentAt) {
        this.lastStatusSentAt = lastStatusSentAt;
    }
}
