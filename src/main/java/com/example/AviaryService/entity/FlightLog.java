package com.example.AviaryService.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;

@Entity
@Table(name = "flight_logs")
public class FlightLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = true)
    private String fromAirport;

    @Column(nullable = true)
    private String toAirport;

    @Column(nullable = true)
    private Double blockTimeIn;  // Use Double for decimal hours if needed

    @Column(nullable = true)
    private Double blockTimeOut;

    @Column(nullable = true)
    private Double timeInServiceIn;

    @Column(nullable = true)
    private Double timeInServiceOut;

    // Engine-start / engine-stop, UTC -- when the blockTimeOut/blockTimeIn
    // readings were taken. Lets the logbook be sorted chronologically and
    // lets HoursService.recomputeChain place this flight correctly relative
    // to others instead of always appending it to the end.
    @Column(nullable = true)
    private java.time.Instant blockTimeStart;

    @Column(nullable = true)
    private java.time.Instant blockTimeEnd;

    // Wheels-up / wheels-down, UTC -- when the timeInServiceOut/timeInServiceIn
    // readings were taken. This is also the 14 CFR 1.1 "time in service"
    // clock. What AeroAPI's actual_off/actual_on give us directly.
    @Column(nullable = true)
    private java.time.Instant timeInServiceStart;

    @Column(nullable = true)
    private java.time.Instant timeInServiceEnd;

    // "manual" | "csv" | "aeroapi". Decides how HoursService.recomputeChain
    // treats this row: "manual" readings are trusted as-is and never
    // rewritten (the user may have read them off the real meter, which can
    // legitimately have a gap from the previous flight); "csv"/"aeroapi"
    // readings have no physical meter behind them and get recomputed to fit
    // chronologically whenever a flight is added or deleted. Nullable for
    // rows that predate this field -- see getEffectiveSource().
    @Column(nullable = true)
    private String source;

    // Set only when this row came from an accepted AeroAPI suggestion. Lets
    // deleting this row reset that suggestion back to pending instead of
    // leaving it permanently "accepted" with nothing pointing at it -- see
    // docs/planning/CHANGES.md.
    @Column(nullable = true)
    private String faFlightId;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    @JsonBackReference
    private User user;

    // Constructors
    public FlightLog() {}

    public FlightLog(String fromAirport, String toAirport, Double blockTimeIn, Double blockTimeOut, Double timeInServiceIn, Double timeInServiceOut, User user) {
        this.fromAirport = fromAirport;
        this.toAirport = toAirport;
        this.blockTimeIn = blockTimeIn;
        this.blockTimeOut = blockTimeOut;
        this.timeInServiceIn = timeInServiceIn;
        this.timeInServiceOut = timeInServiceOut;
        this.user = user;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFromAirport() { return fromAirport; }
    public void setFromAirport(String fromAirport) { this.fromAirport = fromAirport; }
    public String getToAirport() { return toAirport; }
    public void setToAirport(String toAirport) { this.toAirport = toAirport; }
    public Double getBlockTimeIn() { return blockTimeIn; }
    public void setBlockTimeIn(Double blockTimeIn) { this.blockTimeIn = blockTimeIn; }
    public Double getBlockTimeOut() { return blockTimeOut; }
    public void setBlockTimeOut(Double blockTimeOut) { this.blockTimeOut = blockTimeOut; }
    public Double getTimeInServiceIn() { return timeInServiceIn; }
    public void setTimeInServiceIn(Double timeInServiceIn) { this.timeInServiceIn = timeInServiceIn; }
    public Double getTimeInServiceOut() { return timeInServiceOut; }
    public void setTimeInServiceOut(Double timeInServiceOut) { this.timeInServiceOut = timeInServiceOut; }
    public java.time.Instant getBlockTimeStart() { return blockTimeStart; }
    public void setBlockTimeStart(java.time.Instant blockTimeStart) { this.blockTimeStart = blockTimeStart; }
    public java.time.Instant getBlockTimeEnd() { return blockTimeEnd; }
    public void setBlockTimeEnd(java.time.Instant blockTimeEnd) { this.blockTimeEnd = blockTimeEnd; }
    public java.time.Instant getTimeInServiceStart() { return timeInServiceStart; }
    public void setTimeInServiceStart(java.time.Instant timeInServiceStart) { this.timeInServiceStart = timeInServiceStart; }
    public java.time.Instant getTimeInServiceEnd() { return timeInServiceEnd; }
    public void setTimeInServiceEnd(java.time.Instant timeInServiceEnd) { this.timeInServiceEnd = timeInServiceEnd; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    // Rows saved before the `source` column existed have it null. Fall back
    // to faFlightId to tell AeroAPI-sourced rows apart from manual ones so
    // old data still recomputes correctly instead of being misread as manual.
    public String getEffectiveSource() {
        if (source != null) return source;
        return faFlightId != null ? "aeroapi" : "manual";
    }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getFaFlightId() { return faFlightId; }
    public void setFaFlightId(String faFlightId) { this.faFlightId = faFlightId; }
}
