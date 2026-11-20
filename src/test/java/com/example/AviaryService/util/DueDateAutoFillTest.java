package com.example.AviaryService.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.example.AviaryService.util.DueDateAutoFill.Cycle;
import com.example.AviaryService.util.DueDateAutoFill.Resolved;

// Spec 007: every acceptance example and edge case for Due Date auto-fill.
class DueDateAutoFillTest {

    private static final Cycle ONE_MONTH = new Cycle(1, "MONTHS", null);
    private static final Cycle THIRTY_DAYS = new Cycle(30, "DAYS", null);
    private static final Cycle ONE_YEAR = new Cycle(1, "YEARS", null);
    private static final Cycle HUNDRED_HOURS = new Cycle(null, null, 100.0);
    private static final Cycle NONE = new Cycle(null, null, null);

    // ── Date math (exact day, owner's choice) ───────────────────────────

    @Test
    void oneMonthFromOct30IsNov30() {
        assertEquals("2026-11-30", DueDateAutoFill.expectedDueDate("2026-10-30", ONE_MONTH));
    }

    @Test
    void thirtyDaysFromOct30IsNov29() {
        assertEquals("2026-11-29", DueDateAutoFill.expectedDueDate("2026-10-30", THIRTY_DAYS));
    }

    @Test
    void monthFromJan31ClampsToEndOfFebruary() {
        assertEquals("2027-02-28", DueDateAutoFill.expectedDueDate("2027-01-31", ONE_MONTH));
        assertEquals("2028-02-29", DueDateAutoFill.expectedDueDate("2028-01-31", ONE_MONTH));
    }

    @Test
    void yearFromLeapDayIsFeb28() {
        assertEquals("2029-02-28", DueDateAutoFill.expectedDueDate("2028-02-29", ONE_YEAR));
    }

    @Test
    void halfTypedOrSillyDatesAreIgnored() {
        assertNull(DueDateAutoFill.expectedDueDate("0002-10-30", ONE_MONTH));
        assertNull(DueDateAutoFill.expectedDueDate("2026-13-01", ONE_MONTH));
        assertNull(DueDateAutoFill.expectedDueDate("", ONE_MONTH));
    }

    @Test
    void legacyDateCellWithHoursUsesTheDatePart() {
        assertEquals("2026-11-30", DueDateAutoFill.expectedDueDate("2026-10-30 12.5", ONE_MONTH));
    }

    @Test
    void incompleteCycleFillsNothing() {
        assertNull(DueDateAutoFill.expectedDueDate("2026-10-30", new Cycle(1, null, null)));
        assertNull(DueDateAutoFill.expectedDueDate("2026-10-30", new Cycle(0, "MONTHS", null)));
        assertNull(DueDateAutoFill.expectedDueHours("1200", new Cycle(null, null, 0.0)));
        assertNull(DueDateAutoFill.expectedDueHours("1200", new Cycle(null, null, -5.0)));
    }

    // ── Hours math and format ───────────────────────────────────────────

    @Test
    void hoursAddTheCycleWithoutFloatNoise() {
        assertEquals("1300.5", DueDateAutoFill.expectedDueHours("1200.5", HUNDRED_HOURS));
        assertEquals("1300.25", DueDateAutoFill.expectedDueHours("1200.25", HUNDRED_HOURS));
        assertEquals("125.0", DueDateAutoFill.expectedDueHours("25", HUNDRED_HOURS));
        assertEquals("0.3", DueDateAutoFill.expectedDueHours("0.1", new Cycle(null, null, 0.2)));
    }

    @Test
    void hoursAlwaysShowOneToThreeDecimals() {
        assertEquals("25.0", DueDateAutoFill.normalizeHours("25"));
        assertEquals("25.0", DueDateAutoFill.normalizeHours("25."));
        assertEquals("25.25", DueDateAutoFill.normalizeHours("25.25"));
        assertEquals("25.123", DueDateAutoFill.normalizeHours("25.12345"));
        assertEquals("25.124", DueDateAutoFill.normalizeHours("25.1235"));
        assertEquals("0.5", DueDateAutoFill.normalizeHours(".5"));
        assertEquals("0.0", DueDateAutoFill.normalizeHours("0"));
        assertEquals("25.0", DueDateAutoFill.normalizeHours(" 25 "));
        assertNull(DueDateAutoFill.normalizeHours(""));
        assertEquals("25.0", DueDateAutoFill.formatHours(25.0));
    }

    @Test
    void nonNumericHoursAreRejectedWithAClearMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> DueDateAutoFill.normalizeHours("abc"));
        assertEquals("Hours must be a number, e.g. 25.0", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> DueDateAutoFill.normalizeHours("-5"));
    }

    @Test
    void legacyTextIsDisplayedAsStored() {
        assertEquals("1200 TT", DueDateAutoFill.displayHours("1200 TT"));
        assertEquals("25.0", DueDateAutoFill.displayHours("25"));
        assertNull(DueDateAutoFill.displayHours(null));
    }

    // ── Who owns the Due Date ───────────────────────────────────────────

    @Test
    void blankDueDateFillsFromLastDone() {
        Resolved r = DueDateAutoFill.resolveDate(null, ONE_MONTH, null, null,
            "2026-10-30", ONE_MONTH, null, false);
        assertEquals("2026-11-30", r.value());
        assertTrue(r.auto());
    }

    @Test
    void autoFilledDueDateFollowsANewLastDone() {
        Resolved r = DueDateAutoFill.resolveDate("2026-10-30", ONE_MONTH, "2026-11-30", true,
            "2026-12-05", ONE_MONTH, "2026-11-30", false);
        assertEquals("2027-01-05", r.value());
        assertTrue(r.auto());
    }

    @Test
    void typedDueDateIsNeverOverwritten() {
        Resolved r = DueDateAutoFill.resolveDate("2026-10-30", ONE_MONTH, "2027-03-01", false,
            "2026-12-05", ONE_MONTH, "2027-03-01", false);
        assertEquals("2027-03-01", r.value());
        assertFalse(r.auto());
    }

    @Test
    void legacyRowMatchingTheCycleCountsAsAuto() {
        // No marker yet (null): due equals last done + cycle, so it follows.
        Resolved r = DueDateAutoFill.resolveDate("2026-10-30", ONE_MONTH, "2026-11-30", null,
            "2026-12-05", ONE_MONTH, "2026-11-30", false);
        assertEquals("2027-01-05", r.value());
    }

    @Test
    void legacyRowNotMatchingTheCycleCountsAsTyped() {
        Resolved r = DueDateAutoFill.resolveDate("2026-10-30", ONE_MONTH, "2027-06-15", null,
            "2026-12-05", ONE_MONTH, "2027-06-15", false);
        assertEquals("2027-06-15", r.value());
        assertFalse(r.auto());
    }

    @Test
    void clearingLastDoneClearsAnAutoDueDateOnly() {
        Resolved auto = DueDateAutoFill.resolveDate("2026-10-30", ONE_MONTH, "2026-11-30", true,
            null, ONE_MONTH, "2026-11-30", false);
        assertNull(auto.value());
        Resolved typed = DueDateAutoFill.resolveDate("2026-10-30", ONE_MONTH, "2027-03-01", false,
            null, ONE_MONTH, "2027-03-01", false);
        assertEquals("2027-03-01", typed.value());
    }

    @Test
    void halfTypedLastDoneKeepsTheDueDateAndStaysAuto() {
        Resolved mid = DueDateAutoFill.resolveDate("2026-10-30", ONE_MONTH, "2026-11-30", true,
            "0002-10-30", ONE_MONTH, "2026-11-30", false);
        assertEquals("2026-11-30", mid.value());
        assertTrue(mid.auto());
        // Next keystroke completes the year: due follows (marker survived).
        Resolved done = DueDateAutoFill.resolveDate("0002-10-30", ONE_MONTH, "2026-11-30", true,
            "2027-10-30", ONE_MONTH, "2026-11-30", false);
        assertEquals("2027-11-30", done.value());
    }

    @Test
    void settingTheCycleLaterFills() {
        Resolved r = DueDateAutoFill.resolveDate("2026-10-30", NONE, null, null,
            "2026-10-30", ONE_MONTH, null, false);
        assertEquals("2026-11-30", r.value());
    }

    @Test
    void removingTheCycleKeepsTheDueDate() {
        Resolved r = DueDateAutoFill.resolveDate("2026-10-30", ONE_MONTH, "2026-11-30", true,
            "2026-10-30", NONE, "2026-11-30", false);
        assertEquals("2026-11-30", r.value());
    }

    @Test
    void ownerEditingTheDueDateTakesOwnership() {
        Resolved r = DueDateAutoFill.resolveDate("2026-10-30", ONE_MONTH, "2026-11-30", true,
            "2026-10-30", ONE_MONTH, "2027-01-15", true);
        assertEquals("2027-01-15", r.value());
        assertFalse(r.auto());
        // ...and removing it is the owner's choice too.
        Resolved cleared = DueDateAutoFill.resolveDate("2026-10-30", ONE_MONTH, "2027-01-15", false,
            "2026-10-30", ONE_MONTH, "", true);
        assertNull(cleared.value());
        assertFalse(cleared.auto());
    }

    // Reported bug: 12 mo -> 1 mo -> delete the number -> Remove the Due Date
    // calendar. The old Due Date (1 month after Last Done) came straight back.
    @Test
    void removedDueDateDoesNotComeBackWhenTheCycleIsEmpty() {
        Cycle noNumber = new Cycle(null, "MONTHS", null);
        // Number deleted: cycle incomplete, auto Due Date kept for now.
        Resolved kept = DueDateAutoFill.resolveDate("2020-12-01", ONE_MONTH, "2021-01-01", true,
            "2020-12-01", noNumber, "2021-01-01", false);
        assertEquals("2021-01-01", kept.value());
        // Owner taps Remove on the Due Date calendar.
        Resolved removed = DueDateAutoFill.resolveDate("2020-12-01", noNumber, kept.value(), kept.auto(),
            "2020-12-01", noNumber, null, true);
        assertNull(removed.value());
        // Any later save with nothing changed keeps it removed.
        Resolved later = DueDateAutoFill.resolveDate("2020-12-01", noNumber, removed.value(), removed.auto(),
            "2020-12-01", noNumber, null, false);
        assertNull(later.value());
    }

    @Test
    void removedDueDateStaysRemovedOnUnrelatedSavesButRefillsWhenInputsChange() {
        // Removed while the cycle is still set.
        Resolved unrelated = DueDateAutoFill.resolveDate("2026-10-30", ONE_MONTH, null, false,
            "2026-10-30", ONE_MONTH, null, false);
        assertNull(unrelated.value());
        // Owner changes Last Done: fills again and becomes auto.
        Resolved newLast = DueDateAutoFill.resolveDate("2026-10-30", ONE_MONTH, null, false,
            "2026-12-05", ONE_MONTH, null, false);
        assertEquals("2027-01-05", newLast.value());
        assertTrue(newLast.auto());
        // Owner sets the cycle number again: fills too.
        Resolved newCycle = DueDateAutoFill.resolveDate("2020-12-01", new Cycle(null, "MONTHS", null), null, false,
            "2020-12-01", ONE_MONTH, null, false);
        assertEquals("2021-01-01", newCycle.value());
        // A typed (non-blank) Due Date is never replaced, even when inputs change.
        Resolved typed = DueDateAutoFill.resolveDate("2026-10-30", ONE_MONTH, "2027-06-01", false,
            "2026-12-05", ONE_MONTH, "2027-06-01", false);
        assertEquals("2027-06-01", typed.value());
    }

    @Test
    void hoursPartWorksIndependently() {
        Resolved h = DueDateAutoFill.resolveHours(null, HUNDRED_HOURS, null, null,
            "1200.5", HUNDRED_HOURS, null, false);
        assertEquals("1300.5", h.value());
        Resolved typed = DueDateAutoFill.resolveHours("1200.5", HUNDRED_HOURS, "1350.0", false,
            "1250.0", HUNDRED_HOURS, null, false);
        assertEquals("1350.0", typed.value());
        // Legacy "1300" equals 1200 + 100, so it counts as auto even without the .0.
        Resolved legacy = DueDateAutoFill.resolveHours("1200", HUNDRED_HOURS, "1300", null,
            "1250", HUNDRED_HOURS, null, false);
        assertEquals("1350.0", legacy.value());
    }
}
