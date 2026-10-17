package com.example.AviaryService.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

// One row per message actually handed to a sender. Backs the per-recipient rate
// limit (docs/specs/ALERTS_SPEC.md "Rate limit") and doubles as an activity trail.
// The daily sweep prunes rows older than 30 days.
@Entity
@Table(name = "alert_send_log", indexes = {
    @Index(name = "idx_alert_send_recipient_sentat", columnList = "recipientId,sentAt")
})
public class AlertSendLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(nullable = false)
    private long recipientId;

    @Column(nullable = false, length = 8)
    private String channel;

    @Column(nullable = false)
    private Instant sentAt;

    public AlertSendLog() {
    }

    public AlertSendLog(long recipientId, AlertRecipient.Channel channel) {
        this.recipientId = recipientId;
        this.channel = channel.name();
        this.sentAt = Instant.now();
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getRecipientId() {
        return recipientId;
    }

    public void setRecipientId(long recipientId) {
        this.recipientId = recipientId;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }
}
