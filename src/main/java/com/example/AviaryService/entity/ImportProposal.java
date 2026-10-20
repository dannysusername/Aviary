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

// A previewed service timeline import from an AI assistant. Nothing reaches
// the timeline until the owner confirms it. Items and rejected rows are kept
// as JSON (they are only ever read back whole). Status: OPEN (or
// NEEDS_FIT_CONFIRMATION when most rows didn't fit) -> CONFIRMED, or
// REPLACED by a newer proposal, or EXPIRED after 24 hours.
@Entity
@Table(name = "import_proposals")
public class ImportProposal {

    public static final String OPEN = "OPEN";
    public static final String NEEDS_FIT_CONFIRMATION = "NEEDS_FIT_CONFIRMATION";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String REPLACED = "REPLACED";
    public static final String EXPIRED = "EXPIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String itemsJson;

    @Column(columnDefinition = "TEXT")
    private String rejectedJson;

    private int submittedCount;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant confirmedAt;

    // The assistant's client_id (CIMD URL) that made the proposal.
    @Column(length = 1000)
    private String connectionClientId;

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getItemsJson() { return itemsJson; }
    public void setItemsJson(String itemsJson) { this.itemsJson = itemsJson; }
    public String getRejectedJson() { return rejectedJson; }
    public void setRejectedJson(String rejectedJson) { this.rejectedJson = rejectedJson; }
    public int getSubmittedCount() { return submittedCount; }
    public void setSubmittedCount(int submittedCount) { this.submittedCount = submittedCount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }
    public String getConnectionClientId() { return connectionClientId; }
    public void setConnectionClientId(String connectionClientId) { this.connectionClientId = connectionClientId; }
}
