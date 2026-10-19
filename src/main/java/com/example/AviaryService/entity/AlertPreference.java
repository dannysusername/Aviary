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

    // While an item stays overdue, re-nudge every this-many days.
    @Column(nullable = false)
    private int overdueRenudgeDays = 7;

    // The date the sweep last evaluated this user -- guards against a double
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
}
