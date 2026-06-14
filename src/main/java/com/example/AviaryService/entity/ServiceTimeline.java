package com.example.AviaryService.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;

@Entity
@Table(name = "service_timelines") // Assuming a table name; adjust if different
public class ServiceTimeline {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column
    private String item;

    @Column
    private boolean isTitle;

    @Column
    private String description;

    @Column
    private String lastDoneDate;

    @Column
    private String lastDoneHours;

    @Column
    private String dueDateDate;

    @Column
    private String dueDateHours;

    @Column
    private String timeLeft;

    @Column
    private Integer timelineOrder;

    // Structured cycle drives the "Complete Maintenance" button.
    // Nullable: a row may have a calendar cycle, an hours cycle, both, or
    // neither (legacy rows where only the free-text `cycle` is filled).
    @Column
    private Integer cycleCalendarValue;

    // Stored as the enum name: "DAYS" | "MONTHS" | "YEARS".
    @Column
    private String cycleCalendarUnit;

    @Column
    private Double cycleHours;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    @JsonBackReference
    private User user;

    // Constructors
    public ServiceTimeline() {}


    public ServiceTimeline(String item, boolean isTitle, String description, String cycleCalendarUnit, Integer cycleCalendarValue, Double cycleHours, String lastDoneDate, String lastDoneHours, String dueDateDate, String dueDateHours, String timeLeft, User user) {
        this.item = item;
        this.isTitle = isTitle;
        this.description = description;
        this.cycleCalendarUnit = cycleCalendarUnit;
        this.cycleCalendarValue = cycleCalendarValue;
        this.cycleHours = cycleHours;
        this.lastDoneDate = lastDoneDate;
        this.lastDoneHours = lastDoneHours;
        this.dueDateDate = dueDateDate;
        this.dueDateHours = dueDateHours;
        this.timeLeft = timeLeft;
        this.user = user;
    }

    public String getLastDoneDate() {
        return lastDoneDate;
    }

    public void setLastDoneDate(String lastDoneDate) {
        this.lastDoneDate = lastDoneDate;
    }

    public String getLastDoneHours() {
        return lastDoneHours;
    }

    public void setLastDoneHours(String lastDoneHours) {
        this.lastDoneHours = lastDoneHours;
    }

    public String getDueDateDate() {
        return dueDateDate;
    }

    public void setDueDateDate(String dueDateDate) {
        this.dueDateDate = dueDateDate;
    }

    public String getDueDateHours() {
        return dueDateHours;
    }

    public void setDueDateHours(String dueDateHours) {
        this.dueDateHours = dueDateHours;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getItem() { return item; }
    public void setItem(String item) { this.item = item; }

    public boolean getIsTitle() { return isTitle; }
    public void setIsTitle(boolean isTitle) { this.isTitle = isTitle; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTimeLeft() { return timeLeft; }
    public void setTimeLeft(String timeLeft) { this.timeLeft = timeLeft; }

    public Integer getTimelineOrder() { return timelineOrder; }
    public void setTimelineOrder(Integer timelineOrder) { this.timelineOrder = timelineOrder; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public Integer getCycleCalendarValue() { return cycleCalendarValue; }
    public void setCycleCalendarValue(Integer cycleCalendarValue) { this.cycleCalendarValue = cycleCalendarValue; }

    public String getCycleCalendarUnit() { return cycleCalendarUnit; }
    public void setCycleCalendarUnit(String cycleCalendarUnit) { this.cycleCalendarUnit = cycleCalendarUnit; }

    public Double getCycleHours() { return cycleHours; }
    public void setCycleHours(Double cycleHours) { this.cycleHours = cycleHours; }
}