package com.example.AviaryService.util;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// Turns the service timeline items an AI assistant pulled out of an owner's
// records into Aviary items -- or into rejected rows with a plain reason.
// Pure (no Spring, no database) so every rule is unit-tested.
//
// Any row with a name is accepted -- owners' records come in every shape, and
// an item with no dates (on condition, or just not tracked yet) is still a
// real item. Nothing is guessed and nothing is lost silently: a value that
// can't be used is left blank and a note on the item says what and why, for
// the owner to see in the proposal. Only rows with no name are rejected. Due
// date / due hours come from last done + cycle with the same math as
// Complete Maintenance.
public final class TimelineImportRules {

    public static final int MAX_ITEMS = 200;
    public static final int MAX_SOURCE_ROW = 500;
    public static final int MAX_CALENDAR_VALUE = 1200;

    public enum ImportFlag {
        AMBIGUOUS_DATE,      // assistant wasn't sure which date was meant (e.g. 03/04/2025)
        FUTURE_DATE,         // last done is after today -- probably a typo
        UNSUPPORTED_CYCLE,   // e.g. "every 50 landings"; kept in the description, not tracked
        CYCLE_INFERRED,      // no cycle given; worked out from last done -> due
        DUPLICATE_EXISTING,  // same name as an item already on the timeline; skipped unless the owner opts in
        DUPLICATE_IN_INPUT,  // same name appears more than once in this import
        ON_CONDITION,        // marked on condition: inspected/replaced when its condition calls for it
        NO_SCHEDULE,         // no due date or interval at all; shows N/A until the owner adds one
        VALUE_DROPPED        // a value couldn't be used and was left blank -- see the item's notes
    }

    // What the assistant sends for one item. Numbers arrive as text so one bad
    // value is noted on that row instead of failing the whole call.
    public record InputItem(String item, String description, Boolean isTitle,
                            String lastDoneDate, String lastDoneHours,
                            String cycleCalendarValue, String cycleCalendarUnit, String cycleHours,
                            String dueDateDate, String dueDateHours,
                            Boolean dateAmbiguous, String sourceRow, Boolean onCondition) {}

    public record ProposedItem(int index, String item, String description, boolean isTitle,
                               String lastDoneDate, Double lastDoneHours,
                               Integer cycleCalendarValue, String cycleCalendarUnit, Double cycleHours,
                               String dueDateDate, Double dueDateHours,
                               List<ImportFlag> flags, String sourceRow, List<String> notes) {}

    public record RejectedRow(String sourceRow, String reason) {}

    public record ItemResult(List<ProposedItem> items, List<RejectedRow> rejected) {}

    // Shown by get_import_format and with every refused submission.
    public static final String FORMAT_EXAMPLE = """
        [
          {"item": "Annual Inspection", "lastDoneDate": "2026-03-14",
           "cycleCalendarValue": "12", "cycleCalendarUnit": "MONTHS",
           "sourceRow": "Annual - 3/14/26 - yearly"},
          {"item": "Oil Change", "lastDoneHours": "1234.5", "cycleHours": "50",
           "sourceRow": "Oil chg @ 1,234.5 hrs every 50"},
          {"item": "Engine", "isTitle": true, "sourceRow": "ENGINE"},
          {"item": "Flexible Fluid Hose", "description": "Replace", "onCondition": true,
           "sourceRow": "Flexible Fluid Hose - Replace - On condition"}
        ]""";

    public static final List<String> RULES = List.of(
        "Include every maintenance item from the owner's records. An item only needs a name; send whatever dates, "
            + "hours, and intervals it has. Never leave out an item because it has no dates or interval.",
        "Mark items the records describe as on condition with onCondition: true.",
        "Values that can't be used are left blank and explained in the item's notes -- the item is still imported.",
        "Title rows (isTitle: true) group the items under them and only need a name.",
        "Dates must be YYYY-MM-DD. If you can't tell which date was meant (03/04/2025), convert your best reading and set dateAmbiguous: true.",
        "Hours are numbers of time in service, 0 or more.",
        "Calendar intervals: a whole number from 1 to 1200 with unit DAYS, MONTHS, or YEARS. Hours intervals: a number above 0.",
        "Any other interval (landings, cycles, ...) can't be tracked: send it as-is and it is kept in the description.",
        "Put the original line in sourceRow so the owner can compare.",
        "Only rows that are not maintenance items at all (or have no name you can find) go in rejectedBySource, with a reason.",
        "At most " + MAX_ITEMS + " items per proposal.");

    private TimelineImportRules() {}

    public enum SubmissionOutcome {
        PROPOSED,   // a normal proposal
        POOR_FIT,   // more than half the rows didn't fit; owner must say to continue
        REJECTED,   // nothing usable -- not maintenance records, or too broken
        TOO_LARGE   // over MAX_ITEMS; split it up
    }

    // The verdict on a whole submission. submitted counts every row the
    // assistant sent (items + rows it couldn't read itself); rejected counts
    // every row that didn't make it into the proposal. Title rows alone don't
    // count as usable -- a list of headings isn't a timeline.
    public static SubmissionOutcome classify(int itemCount, int submitted, int rejected, ItemResult result) {
        if (itemCount > MAX_ITEMS) return SubmissionOutcome.TOO_LARGE;
        boolean anyRealItem = result.items().stream().anyMatch(i -> !i.isTitle());
        if (submitted == 0 || !anyRealItem) return SubmissionOutcome.REJECTED;
        if (rejected * 2 > submitted) return SubmissionOutcome.POOR_FIT;
        return SubmissionOutcome.PROPOSED;
    }

    // Lowercase, trimmed, inner whitespace collapsed -- "Annual  inspection"
    // and "annual inspection" are the same item.
    public static String normalizeName(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public static ItemResult evaluate(List<InputItem> input, Set<String> existingNormalizedNames, LocalDate today) {
        List<ProposedItem> items = new ArrayList<>();
        List<RejectedRow> rejected = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>(); // normalized name -> index into items

        for (InputItem in : input) {
            if (in == null) continue;
            Object result = evaluateOne(in, today);
            if (result instanceof RejectedRow r) {
                rejected.add(r);
                continue;
            }
            ProposedItem p = (ProposedItem) result;
            String key = normalizeName(p.item());
            EnumSet<ImportFlag> flags = p.flags().isEmpty() ? EnumSet.noneOf(ImportFlag.class) : EnumSet.copyOf(p.flags());

            if (existingNormalizedNames.contains(key)) {
                flags.add(ImportFlag.DUPLICATE_EXISTING);
            }
            if (!p.isTitle() && seen.containsKey(key)) {
                int earlier = seen.get(key);
                ProposedItem first = items.get(earlier);
                if (sameContent(first, p)) {
                    // Exact repeat: show once, note the merge.
                    items.set(earlier, withFlags(first, add(first.flags(), ImportFlag.DUPLICATE_IN_INPUT)));
                    continue;
                }
                // Same name, different details: keep both for the owner to sort out.
                items.set(earlier, withFlags(first, add(first.flags(), ImportFlag.DUPLICATE_IN_INPUT)));
                flags.add(ImportFlag.DUPLICATE_IN_INPUT);
            }
            ProposedItem withIndex = withIndexAndFlags(p, items.size() + 1, List.copyOf(flags));
            if (!p.isTitle()) seen.putIfAbsent(key, items.size());
            items.add(withIndex);
        }
        return new ItemResult(List.copyOf(items), List.copyOf(rejected));
    }

    // A ProposedItem, or a RejectedRow when the row has no name.
    private static Object evaluateOne(InputItem in, LocalDate today) {
        String source = sourceOf(in);
        List<String> notes = new ArrayList<>();

        String name = trimToNull(in.item());
        if (name == null) return new RejectedRow(source, "No item name.");
        if (name.length() > Validation.MAX_TEXT) {
            name = name.substring(0, Validation.MAX_TEXT);
            notes.add("Name was cut to " + Validation.MAX_TEXT + " characters.");
        }
        String description = trimToNull(in.description());

        if (Boolean.TRUE.equals(in.isTitle())) {
            return new ProposedItem(0, name, null, true, null, null, null, null, null, null, null, List.of(), source,
                List.copyOf(notes));
        }

        EnumSet<ImportFlag> flags = EnumSet.noneOf(ImportFlag.class);

        LocalDate lastDone = dateOrNote(in.lastDoneDate(), "Last done date", notes, flags);
        LocalDate due = dateOrNote(in.dueDateDate(), "Due date", notes, flags);
        Double lastHours = hoursOrNote(in.lastDoneHours(), "Last done hours", notes, flags);
        Double dueHours = hoursOrNote(in.dueDateHours(), "Due hours", notes, flags);
        if (lastDone != null && lastDone.isAfter(today)) flags.add(ImportFlag.FUTURE_DATE);
        if (Boolean.TRUE.equals(in.dateAmbiguous())) flags.add(ImportFlag.AMBIGUOUS_DATE);
        boolean onCondition = Boolean.TRUE.equals(in.onCondition());
        if (onCondition) flags.add(ImportFlag.ON_CONDITION);

        // Calendar cycle
        Integer calValue = null;
        String calUnit = null;
        String rawUnit = trimToNull(in.cycleCalendarUnit());
        String rawValue = trimToNull(in.cycleCalendarValue());
        if (rawUnit != null || rawValue != null) {
            String unit = normalizeUnit(rawUnit);
            Integer v = parseWholeNumber(rawValue);
            if (unit == null) {
                // Not a unit Aviary can count down (landings, cycles, ...):
                // keep it in the description so it isn't lost.
                String interval = ((rawValue == null ? "" : rawValue + " ") + (rawUnit == null ? "" : rawUnit)).trim();
                flags.add(ImportFlag.UNSUPPORTED_CYCLE);
                description = appendNote(description, "interval: " + interval);
                notes.add("Interval \"" + interval + "\" isn't something Aviary can count down (it tracks days, "
                    + "months, years, and hours), so it was kept in the description instead.");
            } else if (v == null || v < 1 || v > MAX_CALENDAR_VALUE) {
                flags.add(ImportFlag.VALUE_DROPPED);
                notes.add("Calendar interval \"" + (rawValue == null ? "" : rawValue) + " " + rawUnit
                    + "\" isn't a whole number from 1 to " + MAX_CALENDAR_VALUE + ", so it was left blank.");
            } else {
                calValue = v;
                calUnit = unit;
            }
        }

        // Hours cycle
        Double cycleHours = hoursOrNote(in.cycleHours(), "Hours interval", notes, flags);
        if (cycleHours != null && cycleHours <= 0) {
            flags.add(ImportFlag.VALUE_DROPPED);
            notes.add("Hours interval must be above 0, so it was left blank.");
            cycleHours = null;
        }

        // No cycle given, but last done and due are: work it out.
        if (calValue == null && lastDone != null && due != null && due.isAfter(lastDone)) {
            Period p = Period.between(lastDone, due);
            if (p.getDays() == 0 && p.toTotalMonths() > 0 && p.toTotalMonths() <= MAX_CALENDAR_VALUE) {
                long months = p.toTotalMonths();
                if (months % 12 == 0) {
                    calValue = (int) (months / 12);
                    calUnit = "YEARS";
                } else {
                    calValue = (int) months;
                    calUnit = "MONTHS";
                }
            } else {
                long days = java.time.temporal.ChronoUnit.DAYS.between(lastDone, due);
                if (days <= MAX_CALENDAR_VALUE) {
                    calValue = (int) days;
                    calUnit = "DAYS";
                }
            }
            if (calValue != null) flags.add(ImportFlag.CYCLE_INFERRED);
        }
        if (cycleHours == null && lastHours != null && dueHours != null && dueHours > lastHours) {
            cycleHours = Formatting.roundHours(dueHours - lastHours);
            flags.add(ImportFlag.CYCLE_INFERRED);
        }

        // Due values: from last done + cycle when both are known, else as given.
        if (calValue != null && lastDone != null) {
            due = DueDates.nextDueDate(lastDone, calValue, calUnit);
        }
        if (cycleHours != null && lastHours != null) {
            dueHours = Formatting.roundHours(DueDates.nextDueHours(lastHours, cycleHours));
        }

        if (!onCondition && due == null && dueHours == null && calValue == null && cycleHours == null) {
            flags.add(ImportFlag.NO_SCHEDULE);
            notes.add("No due date or interval, so Time Left will show N/A until one is added.");
        }

        if (description != null && description.length() > Validation.MAX_TEXT) {
            description = description.substring(0, Validation.MAX_TEXT);
            notes.add("Description was cut to " + Validation.MAX_TEXT + " characters.");
        }

        return new ProposedItem(0, name, description, false,
            lastDone == null ? null : lastDone.toString(), lastHours,
            calValue, calUnit, cycleHours,
            due == null ? null : due.toString(), dueHours,
            List.copyOf(flags), source, List.copyOf(notes));
    }

    private static LocalDate dateOrNote(String raw, String label, List<String> notes, Set<ImportFlag> flags) {
        try {
            return parseDate(raw, label);
        } catch (IllegalArgumentException e) {
            flags.add(ImportFlag.VALUE_DROPPED);
            notes.add(e.getMessage() + " It was left blank.");
            return null;
        }
    }

    private static Double hoursOrNote(String raw, String label, List<String> notes, Set<ImportFlag> flags) {
        try {
            return parseHours(raw, label);
        } catch (IllegalArgumentException e) {
            flags.add(ImportFlag.VALUE_DROPPED);
            notes.add(e.getMessage() + " It was left blank.");
            return null;
        }
    }

    private static String appendNote(String description, String note) {
        return description == null ? note : description + " (" + note + ")";
    }

    private static String sourceOf(InputItem in) {
        String s = trimToNull(in.sourceRow());
        if (s == null) {
            s = String.join(" | ", java.util.stream.Stream.of(in.item(), in.lastDoneDate(), in.lastDoneHours(),
                    in.cycleCalendarValue(), in.cycleCalendarUnit(), in.cycleHours(), in.dueDateDate(), in.dueDateHours())
                .filter(Objects::nonNull).map(String::trim).filter(x -> !x.isEmpty()).toList());
        }
        return s.length() > MAX_SOURCE_ROW ? s.substring(0, MAX_SOURCE_ROW) : s;
    }

    private static LocalDate parseDate(String raw, String label) {
        String s = trimToNull(raw);
        if (s == null) return null;
        if (!s.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException(label + " \"" + s + "\" is not in YYYY-MM-DD form.");
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(label + " \"" + s + "\" is not a real date.");
        }
    }

    // A number of hours, 0 or more. Thousands separators are allowed
    // ("1,234.5"); anything else throws with a reason for the item's notes.
    private static Double parseHours(String raw, String label) {
        String s = trimToNull(raw);
        if (s == null) return null;
        String cleaned = s.replace(",", "");
        Double v = Parsing.parseDoubleOrNull(cleaned);
        if (v == null || v.isNaN() || v.isInfinite()) {
            throw new IllegalArgumentException(label + " \"" + s + "\" is not a number.");
        }
        if (v < 0) {
            throw new IllegalArgumentException(label + " can't be negative.");
        }
        return Formatting.roundHours(v);
    }

    private static Integer parseWholeNumber(String raw) {
        if (raw == null) return null;
        return Parsing.parseIntOrNull(raw.replace(",", ""));
    }

    // DAYS / MONTHS / YEARS, also accepting the singular.
    private static String normalizeUnit(String raw) {
        if (raw == null) return null;
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (u.equals("DAY") || u.equals("MONTH") || u.equals("YEAR")) u = u + "S";
        return Parsing.normalizeCalendarUnit(u);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean sameContent(ProposedItem a, ProposedItem b) {
        return Objects.equals(a.description(), b.description())
            && Objects.equals(a.lastDoneDate(), b.lastDoneDate())
            && Objects.equals(a.lastDoneHours(), b.lastDoneHours())
            && Objects.equals(a.cycleCalendarValue(), b.cycleCalendarValue())
            && Objects.equals(a.cycleCalendarUnit(), b.cycleCalendarUnit())
            && Objects.equals(a.cycleHours(), b.cycleHours())
            && Objects.equals(a.dueDateDate(), b.dueDateDate())
            && Objects.equals(a.dueDateHours(), b.dueDateHours());
    }

    private static List<ImportFlag> add(List<ImportFlag> flags, ImportFlag flag) {
        Set<ImportFlag> s = new LinkedHashSet<>(flags);
        s.add(flag);
        return List.copyOf(s);
    }

    private static ProposedItem withFlags(ProposedItem p, List<ImportFlag> flags) {
        return withIndexAndFlags(p, p.index(), flags);
    }

    private static ProposedItem withIndexAndFlags(ProposedItem p, int index, List<ImportFlag> flags) {
        return new ProposedItem(index, p.item(), p.description(), p.isTitle(), p.lastDoneDate(), p.lastDoneHours(),
            p.cycleCalendarValue(), p.cycleCalendarUnit(), p.cycleHours(), p.dueDateDate(), p.dueDateHours(),
            flags, p.sourceRow(), p.notes());
    }
}
