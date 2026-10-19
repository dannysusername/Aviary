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

// Someone a user has asked to receive maintenance alerts -- could be the owner's
// own email, a maintenance shop, a partner. See docs/specs/ALERTS_SPEC.md.
//
// A recipient only ever receives mail once status == ACCEPTED. Everything starts
// PENDING and requires the recipient themselves to confirm (user A can't consent
// for user B).
@Entity
@Table(name = "alert_recipients")
public class AlertRecipient {

    public enum Channel { EMAIL, SMS }

    public enum Status { PENDING, ACCEPTED, DECLINED, EXPIRED }

    // PENDING confirmations die after this long; a nudge goes out at the halfway
    // mark. Both handled by the daily sweep.
    public static final int CONFIRM_EXPIRY_DAYS = 14;
    public static final int CONFIRM_REMINDER_DAYS = 7;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 8)
    private String channel;

    @Column(nullable = false)
    private String destination;

    @Column
    private String label;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false, unique = true, length = 64)
    private String confirmToken;

    @Column(nullable = false)
    private Instant createdAt;

    @Column
    private Instant confirmedAt;

    @Column
    private Instant reminderSentAt;

    public AlertRecipient() {
    }

    public AlertRecipient(User user, Channel channel, String destination, String label, String confirmToken) {
        this.user = user;
        this.channel = channel.name();
        this.destination = destination;
        this.label = label;
        this.confirmToken = confirmToken;
        this.status = Status.PENDING.name();
        this.createdAt = Instant.now();
    }

    // -- convenience enum accessors (columns stay String for easy inspection) --

    public Channel getChannelEnum() {
        return Channel.valueOf(channel);
    }

    public void setChannelEnum(Channel c) {
        this.channel = c.name();
    }

    public Status getStatusEnum() {
        return Status.valueOf(status);
    }

    public void setStatusEnum(Status s) {
        this.status = s.name();
    }

    public boolean isAccepted() {
        return Status.ACCEPTED.name().equals(status);
    }

    public boolean isPending() {
        return Status.PENDING.name().equals(status);
    }

    // -- plain getters / setters --

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

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getConfirmToken() {
        return confirmToken;
    }

    public void setConfirmToken(String confirmToken) {
        this.confirmToken = confirmToken;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public Instant getReminderSentAt() {
        return reminderSentAt;
    }

    public void setReminderSentAt(Instant reminderSentAt) {
        this.reminderSentAt = reminderSentAt;
    }
}
