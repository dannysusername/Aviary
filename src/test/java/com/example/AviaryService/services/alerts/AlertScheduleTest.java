package com.example.AviaryService.services.alerts;

import static com.example.AviaryService.services.alerts.AlertSchedule.Reason.DUE_SOON_REMINDER;
import static com.example.AviaryService.services.alerts.AlertSchedule.Reason.NEW_DUE_SOON;
import static com.example.AviaryService.services.alerts.AlertSchedule.Reason.NEW_OVERDUE;
import static com.example.AviaryService.services.alerts.AlertSchedule.Reason.OVERDUE_REMINDER;
import static com.example.AviaryService.services.alerts.AlertSchedule.Reason.STATUS_REPORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.AviaryService.services.alerts.AlertSchedule.Frequency;
import com.example.AviaryService.services.alerts.AlertSchedule.Input;
import com.example.AviaryService.services.alerts.AlertSchedule.Reason;

// Spec 014: every rule in contracts/schedule-rules.md, case by case (matrix A).
// Pure: no Spring, no clock, no database.
class AlertScheduleTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);

    // Builder with the defaults: Due soon every 7, Overdue every 3, Status Off.
    private static final class In {
        int dueSoonEvery = 7, overdueEvery = 3, statusEvery = Frequency.OFF;
        boolean anyDueSoon, anyOverdue, newDueSoon, newOverdue;
        LocalDate lastDueSoon, lastOverdue, lastStatus;

        Set<Reason> decide() {
            return AlertSchedule.decide(new Input(dueSoonEvery, overdueEvery, statusEvery,
                anyDueSoon, anyOverdue, newDueSoon, newOverdue, TODAY, lastDueSoon, lastOverdue, lastStatus));
        }
    }

    private static Set<Reason> none() {
        return EnumSet.noneOf(Reason.class);
    }

    @Test
    void a1_nothingDueSendsNothing() {
        assertEquals(none(), new In().decide());
    }

    @Test
    void a2_newDueSoonSends() {
        In in = new In();
        in.anyDueSoon = true;
        in.newDueSoon = true;
        assertEquals(EnumSet.of(NEW_DUE_SOON), in.decide());
    }

    @Test
    void a3_dueSoonSixDaysAfterLastEmailWaits() {
        In in = new In();
        in.anyDueSoon = true;
        in.lastDueSoon = TODAY.minusDays(6);
        assertEquals(none(), in.decide());
    }

    @Test
    void a4_dueSoonReminderAfterSevenDays() {
        In in = new In();
        in.anyDueSoon = true;
        in.lastDueSoon = TODAY.minusDays(7);
        assertEquals(EnumSet.of(DUE_SOON_REMINDER), in.decide());
    }

    @Test
    void a5_dueSoonOnceNeverRepeats() {
        In in = new In();
        in.dueSoonEvery = Frequency.ONCE;
        in.anyDueSoon = true;
        in.lastDueSoon = TODAY.minusDays(30);
        assertEquals(none(), in.decide());
    }

    @Test
    void a6_dueSoonOffIgnoresNewItems() {
        In in = new In();
        in.dueSoonEvery = Frequency.OFF;
        in.anyDueSoon = true;
        in.newDueSoon = true;
        assertEquals(none(), in.decide());
    }

    @Test
    void a7_overdueReminderAfterThreeDays() {
        In in = new In();
        in.anyOverdue = true;
        in.lastOverdue = TODAY.minusDays(3);
        assertEquals(EnumSet.of(OVERDUE_REMINDER), in.decide());
    }

    @Test
    void a8_overdueOffStillLetsDueSoonThrough() {
        In in = new In();
        in.overdueEvery = Frequency.OFF;
        in.anyOverdue = true;
        in.lastOverdue = TODAY.minusDays(30);
        in.anyDueSoon = true;
        in.newDueSoon = true;
        assertEquals(EnumSet.of(NEW_DUE_SOON), in.decide());
    }

    @Test
    void a9_statusReportAfterSevenDays() {
        In in = new In();
        in.statusEvery = 7;
        in.lastStatus = TODAY.minusDays(7);
        assertEquals(EnumSet.of(STATUS_REPORT), in.decide());
        in.lastStatus = TODAY.minusDays(6);
        assertEquals(none(), in.decide());
    }

    @Test
    void a10_statusReportNeverSentGoesOutNow() {
        In in = new In();
        in.statusEvery = 7;
        assertEquals(EnumSet.of(STATUS_REPORT), in.decide());
    }

    @Test
    void a11_statusAndDueSoonSameDayAreBothReasons() {
        In in = new In();
        in.statusEvery = 7;
        in.lastStatus = TODAY.minusDays(7);
        in.anyDueSoon = true;
        in.lastDueSoon = TODAY.minusDays(7);
        assertEquals(EnumSet.of(STATUS_REPORT, DUE_SOON_REMINDER), in.decide());
    }

    @Test
    void a12_straightToOverdue() {
        In in = new In();
        in.anyOverdue = true;
        in.newOverdue = true;
        assertEquals(EnumSet.of(NEW_OVERDUE), in.decide());
    }

    @Test
    void overdueOnceSendsWhenNewButNeverReminds() {
        In in = new In();
        in.overdueEvery = Frequency.ONCE;
        in.anyOverdue = true;
        in.newOverdue = true;
        assertEquals(EnumSet.of(NEW_OVERDUE), in.decide());
        in.newOverdue = false;
        in.lastOverdue = TODAY.minusDays(40);
        assertEquals(none(), in.decide());
    }

    @Test
    void reminderWithNoPreviousEmailGoesOut() {
        In in = new In();
        in.anyOverdue = true; // overdue, never emailed about it
        assertEquals(EnumSet.of(OVERDUE_REMINDER), in.decide());
    }

    @Test
    void a13_frequencyParseAndLabel() {
        assertEquals(Frequency.OFF, Frequency.parse("off", true));
        assertEquals(Frequency.OFF, Frequency.parse("Off", false));
        assertEquals(Frequency.ONCE, Frequency.parse("once", true));
        assertEquals(7, Frequency.parse("7", true));
        assertEquals(14, Frequency.parse(14, true));
        assertEquals(90, Frequency.parse(500, true));   // clamped
        assertEquals(1, Frequency.parse(-5, true));     // clamped (only -1 via "once")
        assertThrows(IllegalArgumentException.class, () -> Frequency.parse("once", false));
        assertThrows(IllegalArgumentException.class, () -> Frequency.parse("weekly", true));
        assertThrows(IllegalArgumentException.class, () -> Frequency.parse(null, true));
        assertEquals("off", Frequency.toJson(Frequency.OFF));
        assertEquals("once", Frequency.toJson(Frequency.ONCE));
        assertEquals(3, Frequency.toJson(3));
    }
}
