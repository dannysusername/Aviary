package com.example.AviaryService.services.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.example.AviaryService.entity.ServiceTimeline;

//Checks the "time left" wording in alert emails. Overdue parts come first
class AlertDigestServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 18);
    private static final Double HOURS_NOW = 2500.0;

    private static String text(String dueDate, String dueHours) {
        ServiceTimeline t = new ServiceTimeline();
        t.setDueDateDate(dueDate);
        t.setDueDateHours(dueHours);
        return AlertDigestService.timeLeftText(t, HOURS_NOW, TODAY);
    }

    @Test 
    void hoursOverdue_daysLeft_overdueComesFirst() {
        assertEquals("480.0 hrs overdue · 106 days left", text("2027-02-01", "2020"));
    }

    @Test
    void bothOverdue() {
        assertEquals("78 days, 630.0 hrs overdue", text("2026-08-01", "1870"));
    }

    @Test 
    void bothLeft() {
        assertEquals("12 days, 8.0 hrs left", text("2026-10-30", "2508"));
    }

    @Test 
    void onlyDate() {
        assertEquals("12 days left", text("2026-10-30", null));
    }

    @Test
    void neither() {
        assertEquals("", text(null, null));
    }

    
}
