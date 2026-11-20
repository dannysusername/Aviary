package com.example.AviaryService.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Set;

// Spec 007: fills a Status Sheet row's Due Date from Last Done + cycle.
//
// Pure functions only (no Spring, no repositories) so every rule is unit
// tested in DueDateAutoFillTest. The date math is DueDates.nextDueDate, the
// same one Complete Maintenance uses, so the two can never disagree.
//
// Who owns a Due Date part (date or hours) is tracked by an "auto" marker:
//   TRUE  -> the app filled it; it follows Last Done + cycle.
//   FALSE -> the owner typed it; it is never overwritten.
//   null  -> row from before this feature; decided by comparison: blank, or
//            equal to what the cycle gives for the stored Last Done, = auto.
public final class DueDateAutoFill {

    private DueDateAutoFill() {
    }

    private static final Set<String> UNITS = Set.of("DAYS", "MONTHS", "YEARS");

    // Dates outside this range are treated as half-typed (e.g. year "0002").
    private static final int MIN_YEAR = 1900;
    private static final int MAX_YEAR = 2200;

    public record Cycle(Integer calendarValue, String calendarUnit, Double hours) {
        public boolean hasCalendar() {
            return calendarValue != null && calendarValue > 0 && calendarUnit != null && UNITS.contains(calendarUnit);
        }

        public boolean hasHours() {
            return hours != null && Double.isFinite(hours) && hours > 0;
        }
    }

    // The value to save for one Due Date part, and whether it is auto-filled.
    public record Resolved(String value, boolean auto) {
    }

    // ── Hours format: at least 1 and at most 3 decimals ─────────────────

    // 25 -> "25.0", 25.25 -> "25.25", 25.12345 -> "25.123". null for null/NaN.
    public static String formatHours(Double hours) {
        if (hours == null || !Double.isFinite(hours)) {
            return null;
        }
        return formatHours(BigDecimal.valueOf(hours));
    }

    private static String formatHours(BigDecimal v) {
        BigDecimal r = v.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
        if (r.scale() < 1) {
            r = r.setScale(1, RoundingMode.UNNECESSARY);
        }
        return r.toPlainString();
    }

    // A plain non-negative number ("25", "25.5", ".5", " 25 "), else null.
    public static BigDecimal parseHours(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (!s.matches("\\d+(\\.\\d*)?|\\.\\d+")) {
            return null;
        }
        return new BigDecimal(s);
    }

    // Normalizes typed hours to the display format. Blank -> null. Throws
    // IllegalArgumentException with the owner-facing message if it is not
    // a number.
    public static String normalizeHours(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        BigDecimal v = parseHours(raw);
        if (v == null) {
            throw new IllegalArgumentException("Hours must be a number, e.g. 25.0");
        }
        return formatHours(v);
    }

    // Display-only: numeric text gets the decimal format; anything else
    // (legacy "1200 TT") is shown as stored.
    public static String displayHours(String stored) {
        BigDecimal v = parseHours(stored);
        return v == null ? stored : formatHours(v);
    }

    // ── Expected due values ────────────────────────────────────────────

    // A complete, sensible ISO date ("2026-10-30"), or null. Legacy cells
    // that append hours ("2026-10-30 12.5") are read by their first token.
    public static LocalDate validDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String token = raw.trim().split("\\s+")[0];
        try {
            LocalDate d = LocalDate.parse(token);
            return (d.getYear() < MIN_YEAR || d.getYear() > MAX_YEAR) ? null : d;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public static String expectedDueDate(String lastDoneDate, Cycle cycle) {
        LocalDate last = validDate(lastDoneDate);
        if (last == null || cycle == null || !cycle.hasCalendar()) {
            return null;
        }
        return DueDates.nextDueDate(last, cycle.calendarValue(), cycle.calendarUnit()).toString();
    }

    public static String expectedDueHours(String lastDoneHours, Cycle cycle) {
        BigDecimal last = parseHours(lastDoneHours);
        if (last == null || cycle == null || !cycle.hasHours()) {
            return null;
        }
        return formatHours(last.add(BigDecimal.valueOf(cycle.hours())));
    }

    // ── Deciding what to save ──────────────────────────────────────────

    // storedX = values on the row before this save; newX = values after.
    // touched = the owner edited this Due Date part in this save.
    public static Resolved resolveDate(String storedLastDone, Cycle storedCycle, String storedDue, Boolean storedAuto,
                                       String newLastDone, Cycle newCycle, String submittedDue, boolean touched) {
        String newExpected = expectedDueDate(newLastDone, newCycle);
        if (touched) {
            // Owner typed or removed it. Removed (blank) stays theirs too, so a
            // later unrelated save does not bring it back.
            String v = blankToNull(submittedDue);
            return new Resolved(v, v != null && sameDate(v, newExpected));
        }
        boolean auto = storedAuto != null
            ? storedAuto
            : isBlank(storedDue) || sameDate(storedDue, expectedDueDate(storedLastDone, storedCycle));
        if (!auto) {
            // An owner-removed (blank) Due Date fills again only when Last Done
            // or the cycle actually changes.
            if (isBlank(storedDue) && newExpected != null
                    && inputsChanged(storedLastDone, storedCycle, newLastDone, newCycle)) {
                return new Resolved(newExpected, true);
            }
            return new Resolved(storedDue, false);
        }
        if (newExpected != null) {
            return new Resolved(newExpected, true);
        }
        if (isBlank(newLastDone)) {
            return new Resolved(null, true);       // Last Done cleared: clear the auto Due Date
        }
        return new Resolved(storedDue, true);      // half-typed date or no cycle: keep as is
    }

    public static Resolved resolveHours(String storedLastDone, Cycle storedCycle, String storedDue, Boolean storedAuto,
                                        String newLastDone, Cycle newCycle, String submittedDue, boolean touched) {
        String newExpected = expectedDueHours(newLastDone, newCycle);
        if (touched) {
            String v = blankToNull(submittedDue);
            return new Resolved(v, v != null && sameHours(v, newExpected));
        }
        boolean auto = storedAuto != null
            ? storedAuto
            : isBlank(storedDue) || sameHours(storedDue, expectedDueHours(storedLastDone, storedCycle));
        if (!auto) {
            if (isBlank(storedDue) && newExpected != null
                    && inputsChanged(storedLastDone, storedCycle, newLastDone, newCycle)) {
                return new Resolved(newExpected, true);
            }
            return new Resolved(storedDue, false);
        }
        if (newExpected != null) {
            return new Resolved(newExpected, true);
        }
        if (isBlank(newLastDone)) {
            return new Resolved(null, true);
        }
        return new Resolved(storedDue, true);
    }

    private static boolean inputsChanged(String storedLastDone, Cycle storedCycle, String newLastDone, Cycle newCycle) {
        return !sameText(storedLastDone, newLastDone) || !Objects.equals(storedCycle, newCycle);
    }

    static boolean sameDate(String a, String b) {
        LocalDate da = validDate(a), db = validDate(b);
        return da != null && da.equals(db);
    }

    static boolean sameHours(String a, String b) {
        BigDecimal ha = parseHours(a), hb = parseHours(b);
        return ha != null && hb != null && ha.setScale(3, RoundingMode.HALF_UP).compareTo(hb.setScale(3, RoundingMode.HALF_UP)) == 0;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    // Convenience for callers comparing typed text with what is stored.
    public static boolean sameText(String a, String b) {
        return Objects.equals(blankToNull(a), blankToNull(b));
    }
}
