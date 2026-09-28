package com.example.AviaryService.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.example.AviaryService.services.AlertLevel;

class DueDatesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 8);

    @Test
    void daysUntil_handlesPastFutureAndJunk() {
        assertEquals(10L, DueDates.daysUntil("2026-09-18", TODAY));
        assertEquals(-3L, DueDates.daysUntil("2026-09-05", TODAY));
        assertEquals(0L, DueDates.daysUntil("2026-09-08", TODAY));
        // legacy "date hours" combined string -- only the date token is read
        assertEquals(2L, DueDates.daysUntil("2026-09-10 1234.5", TODAY));
        assertNull(DueDates.daysUntil(null, TODAY));
        assertNull(DueDates.daysUntil("   ", TODAY));
        assertNull(DueDates.daysUntil("not-a-date", TODAY));
    }

    @Test
    void hoursUntil_subtractsCurrentTimeInService() {
        assertEquals(50.0, DueDates.hoursUntil("1250", 1200.0));
        assertEquals(-10.0, DueDates.hoursUntil("1190", 1200.0));
        assertNull(DueDates.hoursUntil("1250", null));
        assertNull(DueDates.hoursUntil(null, 1200.0));
    }

    @Test
    void classify_datePath() {
        // 30-day / 10-hour thresholds
        assertEquals(AlertLevel.OK,
            DueDates.classify("2026-12-01", null, null, TODAY, 30, 10));
        assertEquals(AlertLevel.DUE_SOON,
            DueDates.classify("2026-09-20", null, null, TODAY, 30, 10));
        assertEquals(AlertLevel.OVERDUE,
            DueDates.classify("2026-09-01", null, null, TODAY, 30, 10));
    }

    @Test
    void classify_takesWorseOfDateAndHours() {
        // date says OK (far out), hours say OVERDUE -> OVERDUE wins
        assertEquals(AlertLevel.OVERDUE,
            DueDates.classify("2027-01-01", "1190", 1200.0, TODAY, 30, 10));
        // date says DUE_SOON, hours say OK -> DUE_SOON
        assertEquals(AlertLevel.DUE_SOON,
            DueDates.classify("2026-09-20", "5000", 1200.0, TODAY, 30, 10));
    }

    @Test
    void classify_noDueInfoIsOk() {
        assertEquals(AlertLevel.OK,
            DueDates.classify(null, null, 1200.0, TODAY, 30, 10));
    }

    // Smart Time Left -- must match formatCalendarTimeLeft in dashboard.js.
    @Test
    void formatCalendarTimeLeft_usesDaysThenMonthsThenYears() {
        assertEquals("0 days left", DueDates.formatCalendarTimeLeft(TODAY, TODAY));
        assertEquals("1 day left", DueDates.formatCalendarTimeLeft(TODAY.plusDays(1), TODAY));
        assertEquals("45 days left", DueDates.formatCalendarTimeLeft(TODAY.plusDays(45), TODAY));
        assertEquals("59 days left", DueDates.formatCalendarTimeLeft(TODAY.plusDays(59), TODAY));
        assertEquals("1 mo 30 days left", DueDates.formatCalendarTimeLeft(TODAY.plusDays(60), TODAY));
        assertEquals("5 mo 12 days left", DueDates.formatCalendarTimeLeft(TODAY.plusMonths(5).plusDays(12), TODAY));
        assertEquals("4 mo left", DueDates.formatCalendarTimeLeft(TODAY.plusMonths(4), TODAY));
        assertEquals("1 yr left", DueDates.formatCalendarTimeLeft(TODAY.plusYears(1), TODAY));
        assertEquals("1 yr 1 mo left", DueDates.formatCalendarTimeLeft(TODAY.plusDays(400), TODAY));
        assertEquals("2 yrs 3 mo left", DueDates.formatCalendarTimeLeft(TODAY.plusYears(2).plusMonths(3).plusDays(9), TODAY));
        assertEquals("12 days overdue", DueDates.formatCalendarTimeLeft(TODAY.minusDays(12), TODAY));
        assertEquals("1 yr 2 mo overdue", DueDates.formatCalendarTimeLeft(TODAY.minusMonths(14).minusDays(3), TODAY));
    }

    @Test
    void formatTimeLeft_combinesCalendarAndHours() {
        assertEquals("12 days left\n181.3 hours left",
            DueDates.formatTimeLeft("2026-09-20", "1600.0", TODAY, 1418.7));
        assertEquals("50 hours left", DueDates.formatTimeLeft(null, "1250", TODAY, 1200.0));
        assertEquals("2.5 hours overdue", DueDates.formatTimeLeft("", "1197.5", TODAY, 1200.0));
        assertEquals("N/A", DueDates.formatTimeLeft(null, null, TODAY, 1200.0));
    }
}
