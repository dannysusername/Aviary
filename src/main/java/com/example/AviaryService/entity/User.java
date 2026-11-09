package com.example.AviaryService.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.example.AviaryService.config.AeroApiKeyConverter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long Id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<ServiceTimeline> serviceTimeline;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<FlightLog> flightLogs;

    // The owner's fleet. Every account has at least one; the per-plane info
    // card, hours and baselines that used to be columns here now live on
    // Aircraft. The old users columns stay in the table, unmapped, for one
    // release so a code rollback still finds them (AircraftBackfill reads
    // them once). See specs/003-multi-aircraft-fleet/research.md R1, R3.
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
    @OrderBy("id ASC")
    @JsonIgnore
    private List<Aircraft> aircraft = new ArrayList<>();

    // The aircraft the dashboard opens on. Null or dangling -> first aircraft.
    @ManyToOne
    @JoinColumn(name = "last_viewed_aircraft_id")
    @JsonIgnore
    private Aircraft lastViewedAircraft;

    @Convert(converter = AeroApiKeyConverter.class)
    @Column
    private String aeroApiKey;

    // When the account was created. Set once on first save, never changed.
    // Null for accounts made before this was recorded (2026-11): those aren't
    // given guessed dates. Feeds the public signup feed (specs/004-public-aviary-stats).
    @Column(name = "created_at", updatable = false)
    @JsonIgnore
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public User(){

    }

    public User(String username, String password){
        this.username = username;
        this.password = password;
        this.aeroApiKey = "";
    }

    public long getId() {
        return Id;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public List<ServiceTimeline> getServiceTimeline() {
        return serviceTimeline;
    }

    public void setId(long id) {
        Id = id;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setServiceTimeline(List<ServiceTimeline> serviceTimeline) {
        this.serviceTimeline = serviceTimeline;
    }

    public List<FlightLog> getFlightLogs() {
        return flightLogs;
    }

    public void setFlightLogs(List<FlightLog> flightLogs) {
        this.flightLogs = flightLogs;
    }

    public List<Aircraft> getAircraft() { return aircraft; }
    public void setAircraft(List<Aircraft> aircraft) { this.aircraft = aircraft; }

    public Aircraft getLastViewedAircraft() { return lastViewedAircraft; }
    public void setLastViewedAircraft(Aircraft lastViewedAircraft) { this.lastViewedAircraft = lastViewedAircraft; }

    public Instant getCreatedAt() { return createdAt; }
    // Tests only: lets a test place a signup at a known moment.
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getAeroApiKey() {
        return aeroApiKey;
    }

    public void setAeroApiKey(String aeroApiKey) {
        this.aeroApiKey = aeroApiKey;
    }
}