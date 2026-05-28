package com.example.AviaryService.entity.DTO;

public class TimelineUpdateDTO {
    private String item;
    private String description;
    private String cycle;
    private String lastDone;
    private String dueDate;
    private String timeLeft;
    private Integer cycleCalendarValue;
    private String cycleCalendarUnit;
    private Double cycleHours;

    // Default constructor (required for Jackson deserialization)
    public TimelineUpdateDTO() {}

    // Getters and Setters
    public String getItem() { return item; }
    public void setItem(String item) { this.item = item; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCycle() { return cycle; }
    public void setCycle(String cycle) { this.cycle = cycle; }
    public String getLastDone() { return lastDone; }
    public void setLastDone(String lastDone) { this.lastDone = lastDone; }
    public String getDueDate() { return dueDate; }
    public void setDueDate(String dueDate) { this.dueDate = dueDate; }
    public String getTimeLeft() { return timeLeft; }
    public void setTimeLeft(String timeLeft) { this.timeLeft = timeLeft; }

    public Integer getCycleCalendarValue() { return cycleCalendarValue; }
    public void setCycleCalendarValue(Integer v) { this.cycleCalendarValue = v; }

    public String getCycleCalendarUnit() { return cycleCalendarUnit; }
    public void setCycleCalendarUnit(String u) { this.cycleCalendarUnit = u; }

    public Double getCycleHours() { return cycleHours; }
    public void setCycleHours(Double h) { this.cycleHours = h; }
}