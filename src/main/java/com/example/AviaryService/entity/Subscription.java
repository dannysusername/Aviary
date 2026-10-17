package com.example.AviaryService.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "subscriptions")
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    // One flight-sync subscription per user. Every lookup is by user alone now,
    // so this row is the single source of truth for "what is the poller watching".
    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;
    private String tailNumber;
    private boolean active;
    private String status;
    private Instant lastCheckedAt;
    private Instant lastSeenAt;
    //the last time you actually found the plane. Only updated when a position comes back.

    // How often the AeroAPI poller checks this subscription, and at what local
    // hour. Bounds and defaults per docs/specs/ADSB_SYNC_SPEC.md ("Poll interval"):
    // 1-9 days, no settings UI to change these yet -- see "Turning it on" there.
    @Column(nullable = false)
    private int pollIntervalDays = 1;

    @Column(nullable = false)
    private int preferredCheckHour = 3;

    public Subscription() {

    }

    //Once a subscription is created the user will be automatically active and their status will be
    public Subscription(User user, String tailNumber) {
        this.user = user;
        this.tailNumber = tailNumber;
        this.active = true;
        this.status = "OK";

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

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTailNumber() {
        return tailNumber;
    }

    public void setTailNumber(String tailNumber) {
        this.tailNumber = tailNumber;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void setLastCheckedAt(Instant lastCheckedAt) {
        this.lastCheckedAt = lastCheckedAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public int getPollIntervalDays() {
        return pollIntervalDays;
    }

    public void setPollIntervalDays(int pollIntervalDays) {
        this.pollIntervalDays = pollIntervalDays;
    }

    public int getPreferredCheckHour() {
        return preferredCheckHour;
    }

    public void setPreferredCheckHour(int preferredCheckHour) {
        this.preferredCheckHour = preferredCheckHour;
    }

}
