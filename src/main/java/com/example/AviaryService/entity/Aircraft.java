package com.example.AviaryService.entity;

import java.time.Instant;

import com.example.AviaryService.util.Validation;
import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.*;

// One airplane in an owner's fleet. Holds what used to live on User: the
// info card, both hour meters, their manual baseline floors, and where each
// meter's last update came from. Status Sheet rows, flights, flight
// suggestions and the flight-sync subscription each point at one aircraft.
// See specs/003-multi-aircraft-fleet/data-model.md.
@Entity
@Table(name = "aircraft",
    uniqueConstraints = @UniqueConstraint(name = "uq_aircraft_user_tail", columnNames = {"user_id", "tail_number_key"}))
public class Aircraft {

    public static final int MAX_PER_USER = 25;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Column
    private String tailNumber;

    // Upper-cased, whitespace removed, null when blank. Uniqueness per user
    // is checked on this so "n 731 kr" and "N731KR" collide.
    @Column(name = "tail_number_key")
    private String tailNumberKey;

    @Column
    private String makeModel;

    @Column(name = "owner_name") // pre-rename column; keeps every saved name in place (spec 009)
    private String operatorName;

    @Column
    private String makeModelSN;

    @Column(name = "block_time_hours")
    private Double hobbsTimeHours;

    @Column
    private Double timeInServiceHours;

    // Manual "floor" for each meter. Any manual edit on /updateHours writes
    // this too; log-book activity can only raise the displayed value above
    // it, never below. Nullable on purpose: "never set" differs from "0".
    @Column(name = "block_time_manual_baseline")
    private Double hobbsTimeManualBaseline;

    @Column
    private Double timeInServiceManualBaseline;

    @Column(name = "block_time_updated_at")
    private Instant hobbsTimeUpdatedAt;

    @Column
    private Instant timeInServiceUpdatedAt;

    @Column(name = "block_time_updated_source")
    private String hobbsTimeUpdatedSource;

    @Column
    private String timeInServiceUpdatedSource;

    @Column
    private Instant createdAt;

    public Aircraft() {}

    public Aircraft(User user) {
        this.user = user;
        this.hobbsTimeHours = 0.0;
        this.timeInServiceHours = 0.0;
        this.tailNumber = "";
        this.makeModel = "";
        this.operatorName = "";
        this.makeModelSN = "";
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        tailNumberKey = Validation.tailNumberKey(tailNumber);
    }

    // "N731KR", or "Unnamed aircraft" until a tail number is set.
    public String getDisplayName() {
        return tailNumber == null || tailNumber.isBlank() ? "Unnamed aircraft" : tailNumber;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getTailNumber() { return tailNumber; }
    public void setTailNumber(String tailNumber) {
        String normalized = Validation.normalizeTailNumber(tailNumber);
        this.tailNumber = normalized;
        this.tailNumberKey = Validation.tailNumberKey(normalized);
    }

    public String getTailNumberKey() { return tailNumberKey; }

    public String getMakeModel() { return makeModel; }
    public void setMakeModel(String makeModel) { this.makeModel = makeModel; }

    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }

    public String getMakeModelSN() { return makeModelSN; }
    public void setMakeModelSN(String makeModelSN) { this.makeModelSN = makeModelSN; }

    public Double getHobbsTimeHours() { return hobbsTimeHours != null ? hobbsTimeHours : 0.0; }
    public void setHobbsTimeHours(Double hobbsTimeHours) { this.hobbsTimeHours = hobbsTimeHours != null ? hobbsTimeHours : 0.0; }

    public Double getTimeInServiceHours() { return timeInServiceHours != null ? timeInServiceHours : 0.0; }
    public void setTimeInServiceHours(Double timeInServiceHours) { this.timeInServiceHours = timeInServiceHours != null ? timeInServiceHours : 0.0; }

    public Double getHobbsTimeManualBaseline() { return hobbsTimeManualBaseline; }
    public void setHobbsTimeManualBaseline(Double v) { this.hobbsTimeManualBaseline = v; }

    public Double getTimeInServiceManualBaseline() { return timeInServiceManualBaseline; }
    public void setTimeInServiceManualBaseline(Double v) { this.timeInServiceManualBaseline = v; }

    public Instant getHobbsTimeUpdatedAt() { return hobbsTimeUpdatedAt; }
    public void setHobbsTimeUpdatedAt(Instant v) { this.hobbsTimeUpdatedAt = v; }

    public Instant getTimeInServiceUpdatedAt() { return timeInServiceUpdatedAt; }
    public void setTimeInServiceUpdatedAt(Instant v) { this.timeInServiceUpdatedAt = v; }

    public String getHobbsTimeUpdatedSource() { return hobbsTimeUpdatedSource; }
    public void setHobbsTimeUpdatedSource(String v) { this.hobbsTimeUpdatedSource = v; }

    public String getTimeInServiceUpdatedSource() { return timeInServiceUpdatedSource; }
    public void setTimeInServiceUpdatedSource(String v) { this.timeInServiceUpdatedSource = v; }

    public Instant getCreatedAt() { return createdAt; }
}
