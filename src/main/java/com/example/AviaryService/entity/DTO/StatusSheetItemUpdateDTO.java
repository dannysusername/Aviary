package com.example.AviaryService.entity.DTO;

public class StatusSheetItemUpdateDTO {
    private String item;
    private String description;
    private String cycle;
    private String lastDone;
    private String dueDate;
    private String lastDoneDate;
    private String lastDoneHours;
    private String dueDateDate;
    private String dueDateHours;
    private String timeLeft;
    private Integer cycleCalendarValue;
    private String cycleCalendarUnit;
    private Double cycleHours;

    // Default constructor (required for Jackson deserialization)
    public StatusSheetItemUpdateDTO() {}

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

    public String getTimeLeft() { return timeLeft; }
    public void setTimeLeft(String timeLeft) { this.timeLeft = timeLeft; }

    public Integer getCycleCalendarValue() { return cycleCalendarValue; }
    public void setCycleCalendarValue(Integer newCycCalVal) { this.cycleCalendarValue = newCycCalVal; }

    public String getCycleCalendarUnit() { return cycleCalendarUnit; }
    public void setCycleCalendarUnit(String newCycCalUnit) { this.cycleCalendarUnit = newCycCalUnit; }

    public Double getCycleHours() { return cycleHours; }
    public void setCycleHours(Double newCycHrsVal) { this.cycleHours = newCycHrsVal; }

    // Spec 007: true when the owner edited that Due Date part in this save.
    // Both null = an older dashboard.js that predates auto-fill; the server
    // then saves the submitted Due Date as-is (old behavior).
    private Boolean dueDateTouched;
    private Boolean dueHoursTouched;

    public Boolean getDueDateTouched() { return dueDateTouched; }
    public void setDueDateTouched(Boolean v) { this.dueDateTouched = v; }
    public Boolean getDueHoursTouched() { return dueHoursTouched; }
    public void setDueHoursTouched(Boolean v) { this.dueHoursTouched = v; }
}
