package com.example.AviaryService.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

// One AeroAPI-detected flight, pending/accepted/dismissed by the user.
// Unique on (user, faFlightId) -- this is the whole dedupe mechanism: a
// fa_flight_id already seen for this user, in any status, is never inserted
// again. See docs/specs/ADSB_SYNC_SPEC.md.
@Entity
@Table(name = "flight_suggestions",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "fa_flight_id"}))
public class FlightSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // The airplane this row belongs to. Nullable in the schema only so
    // Hibernate's ddl-auto=update can add it to a populated table;
    // AircraftBackfill fills existing rows and the services always set it.
    @ManyToOne
    @JoinColumn(name = "aircraft_id")
    @JsonIgnore
    private Aircraft aircraft;

    @Column(nullable = false)
    private String tailNumber;

    @Column(name = "fa_flight_id", nullable = false)
    private String faFlightId;

    @Column(nullable = false)
    private Instant departureTime;

    @Column(nullable = false)
    private Instant arrivalTime;

    private String origin;
    private String destination;

    // pending / accepted / dismissed
    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private Instant createdAt;

    public FlightSuggestion() {}

    public FlightSuggestion(User user, String tailNumber, String faFlightId,
            Instant departureTime, Instant arrivalTime, String origin, String destination) {
        this.user = user;
        this.tailNumber = tailNumber;
        this.faFlightId = faFlightId;
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
        this.origin = origin;
        this.destination = destination;
        this.status = "pending";
        this.createdAt = Instant.now();
    }

    public long getMinutesAirborne() {
        return java.time.Duration.between(departureTime, arrivalTime).toMinutes();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getTailNumber() { return tailNumber; }
    public void setTailNumber(String tailNumber) { this.tailNumber = tailNumber; }
    public String getFaFlightId() { return faFlightId; }
    public void setFaFlightId(String faFlightId) { this.faFlightId = faFlightId; }
    public Instant getDepartureTime() { return departureTime; }
    public void setDepartureTime(Instant departureTime) { this.departureTime = departureTime; }
    public Instant getArrivalTime() { return arrivalTime; }
    public void setArrivalTime(Instant arrivalTime) { this.arrivalTime = arrivalTime; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Aircraft getAircraft() { return aircraft; }
    public void setAircraft(Aircraft aircraft) { this.aircraft = aircraft; }
}
