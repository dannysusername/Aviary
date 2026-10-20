package com.example.AviaryService.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.AviaryService.util.TimelineImportRules.ImportFlag;
import com.example.AviaryService.util.TimelineImportRules.InputItem;
import com.example.AviaryService.util.TimelineImportRules.ItemResult;
import com.example.AviaryService.util.TimelineImportRules.ProposedItem;
import com.example.AviaryService.util.TimelineImportRules.SubmissionOutcome;

class TimelineImportRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    // Builder-ish helper: item name + key/value pairs.
    private static InputItem item(String name, String... kv) {
        String lastDoneDate = null, lastDoneHours = null, calValue = null, calUnit = null, cycleHours = null;
        String dueDate = null, dueHours = null, description = null, source = null;
        Boolean title = null, ambiguous = null, onCondition = null;
        for (int i = 0; i < kv.length; i += 2) {
            String v = kv[i + 1];
            switch (kv[i]) {
                case "lastDoneDate" -> lastDoneDate = v;
                case "lastDoneHours" -> lastDoneHours = v;
                case "calValue" -> calValue = v;
                case "calUnit" -> calUnit = v;
                case "cycleHours" -> cycleHours = v;
                case "dueDate" -> dueDate = v;
                case "dueHours" -> dueHours = v;
                case "description" -> description = v;
                case "source" -> source = v;
                case "title" -> title = Boolean.valueOf(v);
                case "ambiguous" -> ambiguous = Boolean.valueOf(v);
                case "onCondition" -> onCondition = Boolean.valueOf(v);
                default -> throw new IllegalArgumentException(kv[i]);
            }
        }
        return new InputItem(name, description, title, lastDoneDate, lastDoneHours, calValue, calUnit, cycleHours,
            dueDate, dueHours, ambiguous, source, onCondition);
    }

    private static ItemResult eval(InputItem... items) {
        return TimelineImportRules.evaluate(List.of(items), Set.of(), TODAY);
    }

    private static ProposedItem only(ItemResult r) {
        assertEquals(List.of(), r.rejected(), "unexpected rejects");
        assertEquals(1, r.items().size());
        return r.items().get(0);
    }

    private static String onlyReject(ItemResult r) {
        assertEquals(0, r.items().size(), "expected a reject");
        assertEquals(1, r.rejected().size());
        return r.rejected().get(0).reason();
    }

    @Test
    void nameIsRequired() {
        assertTrue(onlyReject(eval(item("  ", "lastDoneDate", "2026-01-01"))).contains("No item name"));
    }

    @Test
    void longNameIsCutNotRejected() {
        ProposedItem p = only(eval(item("x".repeat(300), "lastDoneDate", "2026-01-01")));
        assertEquals(255, p.item().length());
        assertTrue(p.notes().get(0).contains("255"));
    }

    // Owners' records come in every shape: an item with only a name is still an item.
    @Test
    void itemWithOnlyANameIsImportedAndFlagged() {
        ProposedItem p = only(eval(item("Brake pads", "description", "Replace when worn")));
        assertTrue(p.flags().contains(ImportFlag.NO_SCHEDULE));
        assertEquals("Replace when worn", p.description());
        assertTrue(p.notes().get(0).contains("N/A"));
    }

    @Test
    void onConditionItemIsNotAlsoNoSchedule() {
        ProposedItem p = only(eval(item("Hose", "onCondition", "true")));
        assertFalse(p.flags().contains(ImportFlag.NO_SCHEDULE));
    }

    @Test
    void onConditionItemNeedsNoDates() {
        ProposedItem p = only(eval(item("Flexible Fluid Hose", "description", "Replace", "onCondition", "true")));
        assertTrue(p.flags().contains(ImportFlag.ON_CONDITION));
        assertNull(p.lastDoneDate());
        assertNull(p.dueDateDate());
    }

    @Test
    void onConditionItemKeepsDatesItHas() {
        ProposedItem p = only(eval(item("Battery - Main", "lastDoneDate", "2024-01-05", "onCondition", "true")));
        assertEquals("2024-01-05", p.lastDoneDate());
        assertTrue(p.flags().contains(ImportFlag.ON_CONDITION));
    }

    @Test
    void onConditionCountsAsARealItem() {
        ItemResult r = eval(item("Hose", "onCondition", "true"));
        assertEquals(SubmissionOutcome.PROPOSED, TimelineImportRules.classify(1, 1, 0, r));
    }

    @Test
    void titleRowOnlyNeedsName() {
        ProposedItem p = only(eval(item("Engine", "title", "true")));
        assertTrue(p.isTitle());
        assertNull(p.lastDoneDate());
    }

    @Test
    void badDateIsLeftBlankWithNote() {
        ProposedItem p = only(eval(item("Annual", "lastDoneDate", "3/14/2026", "calValue", "12", "calUnit", "MONTHS")));
        assertNull(p.lastDoneDate());
        assertEquals(12, p.cycleCalendarValue());
        assertTrue(p.flags().contains(ImportFlag.VALUE_DROPPED));
        assertTrue(p.notes().get(0).contains("YYYY-MM-DD"));
        assertTrue(only(eval(item("Annual", "lastDoneDate", "2026-02-30"))).notes().get(0).contains("not a real date"));
    }

    @Test
    void ambiguousDateIsFlagged() {
        ProposedItem p = only(eval(item("Annual", "lastDoneDate", "2025-03-04", "ambiguous", "true")));
        assertTrue(p.flags().contains(ImportFlag.AMBIGUOUS_DATE));
    }

    @Test
    void futureLastDoneIsFlagged() {
        ProposedItem p = only(eval(item("Annual", "lastDoneDate", "2027-01-01")));
        assertTrue(p.flags().contains(ImportFlag.FUTURE_DATE));
        assertFalse(only(eval(item("Annual", "lastDoneDate", "2026-10-20"))).flags().contains(ImportFlag.FUTURE_DATE));
    }

    @Test
    void badHoursAreLeftBlankWithNote() {
        ProposedItem neg = only(eval(item("Oil", "lastDoneHours", "-5", "cycleHours", "50")));
        assertNull(neg.lastDoneHours());
        assertEquals(50.0, neg.cycleHours());
        assertTrue(neg.notes().get(0).contains("negative"));
        assertTrue(only(eval(item("Oil", "lastDoneHours", "abc"))).notes().get(0).contains("not a number"));
        ProposedItem zero = only(eval(item("Oil", "cycleHours", "0")));
        assertNull(zero.cycleHours());
        assertTrue(zero.notes().get(0).contains("above 0"));
    }

    @Test
    void thousandsSeparatorInHoursIsAccepted() {
        assertEquals(1234.5, only(eval(item("Oil", "lastDoneHours", "1,234.5"))).lastDoneHours());
    }

    @Test
    void unsupportedUnitIsDroppedAndFlagged() {
        ProposedItem p = only(eval(item("Gear", "lastDoneDate", "2026-01-01", "calValue", "50", "calUnit", "LANDINGS")));
        assertTrue(p.flags().contains(ImportFlag.UNSUPPORTED_CYCLE));
        assertNull(p.cycleCalendarValue());
        assertNull(p.cycleCalendarUnit());
    }

    @Test
    void unsupportedUnitIsKeptInDescription() {
        ProposedItem p = only(eval(item("Gear", "description", "Inspect", "calValue", "50", "calUnit", "landings")));
        assertEquals("Inspect (interval: 50 landings)", p.description());
        assertTrue(p.flags().contains(ImportFlag.UNSUPPORTED_CYCLE));
        assertTrue(p.flags().contains(ImportFlag.NO_SCHEDULE));
    }

    @Test
    void calendarValueOutOfRangeIsLeftBlankWithNote() {
        for (String bad : new String[] { "0", "1201", "1.5" }) {
            ProposedItem p = only(eval(item("A", "calValue", bad, "calUnit", "MONTHS")));
            assertNull(p.cycleCalendarValue(), bad);
            assertTrue(p.notes().get(0).contains("1 to 1200"), bad);
        }
        assertEquals(1200, only(eval(item("A", "calValue", "1200", "calUnit", "days"))).cycleCalendarValue());
    }

    @Test
    void singularUnitIsAccepted() {
        assertEquals("YEARS", only(eval(item("A", "calValue", "1", "calUnit", "year"))).cycleCalendarUnit());
    }

    @Test
    void cycleInferredFromLastDoneAndDue() {
        ProposedItem yearly = only(eval(item("Annual", "lastDoneDate", "2026-03-14", "dueDate", "2027-03-14")));
        assertEquals(1, yearly.cycleCalendarValue());
        assertEquals("YEARS", yearly.cycleCalendarUnit());
        assertTrue(yearly.flags().contains(ImportFlag.CYCLE_INFERRED));

        ProposedItem monthly = only(eval(item("ELT", "lastDoneDate", "2026-01-10", "dueDate", "2026-07-10")));
        assertEquals(6, monthly.cycleCalendarValue());
        assertEquals("MONTHS", monthly.cycleCalendarUnit());

        ProposedItem daily = only(eval(item("Check", "lastDoneDate", "2026-01-10", "dueDate", "2026-02-19")));
        assertEquals(40, daily.cycleCalendarValue());
        assertEquals("DAYS", daily.cycleCalendarUnit());

        ProposedItem hours = only(eval(item("Oil", "lastDoneHours", "1200", "dueHours", "1250")));
        assertEquals(50.0, hours.cycleHours());
        assertTrue(hours.flags().contains(ImportFlag.CYCLE_INFERRED));
    }

    @Test
    void dueDateComputedForEachUnit() {
        assertEquals("2026-04-10", only(eval(item("A", "lastDoneDate", "2026-01-10", "calValue", "90", "calUnit", "DAYS"))).dueDateDate());
        assertEquals("2026-04-10", only(eval(item("A", "lastDoneDate", "2026-01-10", "calValue", "3", "calUnit", "MONTHS"))).dueDateDate());
        assertEquals("2028-01-10", only(eval(item("A", "lastDoneDate", "2026-01-10", "calValue", "2", "calUnit", "YEARS"))).dueDateDate());
    }

    @Test
    void dueDateClampsAtMonthEndAndLeapDay() {
        assertEquals("2026-02-28", only(eval(item("A", "lastDoneDate", "2026-01-31", "calValue", "1", "calUnit", "MONTHS"))).dueDateDate());
        assertEquals("2028-02-29", only(eval(item("A", "lastDoneDate", "2028-01-31", "calValue", "1", "calUnit", "MONTHS"))).dueDateDate());
        assertEquals("2025-02-28", only(eval(item("A", "lastDoneDate", "2024-02-29", "calValue", "1", "calUnit", "YEARS"))).dueDateDate());
    }

    @Test
    void dueHoursAreLastDonePlusCycle() {
        ProposedItem p = only(eval(item("Oil", "lastDoneHours", "1234.5", "cycleHours", "50")));
        assertEquals(1284.5, p.dueDateHours());
    }

    @Test
    void givenDueKeptWhenItCannotBeComputed() {
        ProposedItem p = only(eval(item("ELT battery", "dueDate", "2027-06-01")));
        assertEquals("2027-06-01", p.dueDateDate());
        assertNull(p.cycleCalendarUnit());
    }

    @Test
    void duplicateOfExistingIsFlagged() {
        ItemResult r = TimelineImportRules.evaluate(
            List.of(item("annual   INSPECTION", "lastDoneDate", "2026-01-01")),
            Set.of(TimelineImportRules.normalizeName("Annual Inspection")), TODAY);
        assertTrue(only(r).flags().contains(ImportFlag.DUPLICATE_EXISTING));
    }

    @Test
    void exactRepeatInInputIsMerged() {
        ItemResult r = eval(item("Oil", "lastDoneHours", "100", "cycleHours", "50"),
                            item("oil", "lastDoneHours", "100", "cycleHours", "50"));
        ProposedItem p = only(r);
        assertTrue(p.flags().contains(ImportFlag.DUPLICATE_IN_INPUT));
    }

    @Test
    void sameNameDifferentDetailsKeepsBoth() {
        ItemResult r = eval(item("Oil", "lastDoneHours", "100", "cycleHours", "50"),
                            item("Oil", "lastDoneHours", "300", "cycleHours", "50"));
        assertEquals(2, r.items().size());
        assertTrue(r.items().get(0).flags().contains(ImportFlag.DUPLICATE_IN_INPUT));
        assertTrue(r.items().get(1).flags().contains(ImportFlag.DUPLICATE_IN_INPUT));
    }

    @Test
    void indexesAreOneBasedInOrderAndSourceKept() {
        ItemResult r = eval(item("Engine", "title", "true"),
                            item("Oil", "lastDoneHours", "100", "source", "Oil chg 100"),
                            item("", "source", "garbage row"),
                            item("Annual", "lastDoneDate", "2026-01-01"));
        assertEquals(List.of(1, 2, 3), r.items().stream().map(ProposedItem::index).toList());
        assertEquals("Oil chg 100", r.items().get(1).sourceRow());
        assertEquals("garbage row", r.rejected().get(0).sourceRow());
    }

    @Test
    void longSourceRowIsTruncated() {
        ProposedItem p = only(eval(item("Oil", "lastDoneHours", "1", "source", "y".repeat(800))));
        assertEquals(TimelineImportRules.MAX_SOURCE_ROW, p.sourceRow().length());
    }
    // ---- whole-submission verdict ----

    private static SubmissionOutcome classify(InputItem... items) {
        ItemResult r = eval(items);
        return TimelineImportRules.classify(items.length, items.length, r.rejected().size(), r);
    }

    private static InputItem junk() {
        return item("", "source", "not maintenance");
    }

    private static InputItem good(String name) {
        return item(name, "lastDoneHours", "10", "cycleHours", "50");
    }

    @Test
    void nothingMappedIsRejected() {
        assertEquals(SubmissionOutcome.REJECTED, classify(junk(), junk()));
        assertEquals(SubmissionOutcome.REJECTED, TimelineImportRules.classify(0, 0, 0, eval()));
    }

    @Test
    void onlyTitleRowsIsRejected() {
        assertEquals(SubmissionOutcome.REJECTED, classify(item("Engine", "title", "true"), junk()));
    }

    @Test
    void moreThanHalfRejectedIsPoorFit() {
        assertEquals(SubmissionOutcome.POOR_FIT, classify(good("A"), junk(), junk()));
    }

    @Test
    void exactlyHalfRejectedIsStillProposed() {
        assertEquals(SubmissionOutcome.PROPOSED, classify(good("A"), junk()));
    }

    @Test
    void over200IsTooLarge() {
        assertEquals(SubmissionOutcome.TOO_LARGE, TimelineImportRules.classify(201, 201, 0, eval(good("A"))));
        assertEquals(SubmissionOutcome.PROPOSED, TimelineImportRules.classify(200, 200, 0, eval(good("A"))));
    }
}
