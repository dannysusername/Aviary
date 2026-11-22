package com.example.AviaryService.services.alerts;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.AlertPreference;
import com.example.AviaryService.entity.StatusSheetItem;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.StatusSheetItemRepository;
import com.example.AviaryService.util.DueDates;
import com.example.AviaryService.util.Formatting;

// Decides what goes in a user's alert message. Checks each maintenance item
// in the whole fleet against the user's alert settings and writes the
// subject and body: one email for every aircraft, grouped by tail number.
//   buildDigest(...) -- only reads; decides if anything should be sent
//   markFired(...)   -- remembers what was alerted, so an item isn't alerted
//                       again unless it gets worse
// See docs/specs/ALERTS_SPEC.md ("How it fires", "Digest content").
@Service
public class AlertDigestService {

    private final StatusSheetItemRepository statusSheetItemRepository;
    private final AircraftRepository aircraftRepository;
    private final AlertEmailRenderer renderer;
    private final java.time.Clock clock;

    public AlertDigestService(StatusSheetItemRepository statusSheetItemRepository,
            AircraftRepository aircraftRepository, AlertEmailRenderer renderer, java.time.Clock clock) {
        this.statusSheetItemRepository = statusSheetItemRepository;
        this.aircraftRepository = aircraftRepository;
        this.renderer = renderer;
        this.clock = clock;
    }

    public AlertDigest buildDigest(User user, AlertPreference prefs, boolean baseline) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        List<Aircraft> fleet = aircraftRepository.findByUserOrderByIdAsc(user);

        // Every row across the fleet, grouped by aircraft. Each hours-cycle
        // row is judged against ITS OWN aircraft's time in service, never
        // another plane's (Constitution II).
        List<StatusSheetItem> rows = statusSheetItemRepository.findByUserOrderByAircraftIdAscStatusSheetOrderAsc(user);

        List<Row> evaluated = new ArrayList<>();
        for (StatusSheetItem t : rows) {
            if (t.getIsTitle()) {
                continue;
            }
            Double tis = timeInServiceOf(t);
            AlertLevel current = DueDates.classify(
                t.getDueDateDate(), t.getDueDateHours(), tis, today,
                prefs.getLeadTimeDays(), prefs.getLeadTimeHours());
            AlertLevel previous = AlertLevel.parse(t.getAlertLevel());
            evaluated.add(new Row(t, current, previous, timeLeftText(t, tis, today),
                sortKey(t, tis, today, prefs.getLeadTimeDays(), prefs.getLeadTimeHours())));
        }

        int overdue = (int) evaluated.stream().filter(r -> r.current == AlertLevel.OVERDUE).count();
        int dueSoon = (int) evaluated.stream().filter(r -> r.current == AlertLevel.DUE_SOON).count();

        List<Row> changed = evaluated.stream().filter(r -> r.current.worseThan(r.previous)).toList();

        // Whether to send is now AlertSchedule's call (spec 014); this only
        // reports what changed. shouldSend stays for "something got worse".
        boolean shouldSend = baseline || !changed.isEmpty();
        boolean newDueSoon = changed.stream().anyMatch(r -> r.current == AlertLevel.DUE_SOON);
        boolean newOverdue = changed.stream().anyMatch(r -> r.current == AlertLevel.OVERDUE);

        String subject = buildSubject(fleet, overdue, dueSoon);
        LeadTimes lead = LeadTimes.of(prefs);
        AlertDigest digest = new AlertDigest(shouldSend, subject, overdue, dueSoon, Instant.now(clock), baseline,
            buildSections(fleet, evaluated, lead), lead);
        digest.setFacts(newDueSoon, newOverdue, prefs.zone());
        digest.setBody(renderer.text(digest, null));
        return digest;
    }

    // Persist current levels so the next run only fires on a change. Called after
    // a successful send and for the baseline.
    public void markFired(User user, AlertPreference prefs) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        Instant now = Instant.now(clock);

        List<StatusSheetItem> rows = statusSheetItemRepository.findByUserOrderByAircraftIdAscStatusSheetOrderAsc(user);
        for (StatusSheetItem t : rows) {
            if (t.getIsTitle()) {
                continue;
            }
            AlertLevel current = DueDates.classify(
                t.getDueDateDate(), t.getDueDateHours(), timeInServiceOf(t), today,
                prefs.getLeadTimeDays(), prefs.getLeadTimeHours());
            t.setAlertLevel(current.stored());
            if (current != AlertLevel.OK) {
                t.setAlertLastFiredAt(now);
            }
        }
        statusSheetItemRepository.saveAll(rows);
    }

    private static Double timeInServiceOf(StatusSheetItem t) {
        return t.getAircraft() == null ? null : t.getAircraft().getTimeInServiceHours();
    }

    // -- the email's content (AlertEmailRenderer writes it) --

    // One section per aircraft, in fleet order: what's overdue or due soon
    // (overdue first, then due soon, most urgent first; ties keep Status
    // Sheet order) and how many items are on track.
    private static List<AlertDigest.AircraftSection> buildSections(List<Aircraft> fleet, List<Row> evaluated,
                                                                    LeadTimes lead) {
        List<AlertDigest.AircraftSection> sections = new ArrayList<>();
        for (Aircraft a : fleet) {
            List<Row> mine = evaluated.stream()
                .filter(r -> r.row.getAircraft() != null && a.getId().equals(r.row.getAircraft().getId()))
                .toList();
            List<AlertDigest.DueItem> due = mine.stream()
                .filter(r -> r.current != AlertLevel.OK)
                .sorted(Comparator.comparingInt((Row r) -> r.current == AlertLevel.OVERDUE ? 0 : 1)
                    .thenComparingDouble(r -> r.sortKey))
                .map(r -> new AlertDigest.DueItem(r.row.getItem(), r.current,
                    r.timeLeft == null ? "" : r.timeLeft.replace('\n', ' '),
                    r.current.worseThan(r.previous), r.sortKey))
                .toList();
            sections.add(new AlertDigest.AircraftSection(
                AlertText.tailOf(a, fleet.size() > 1 ? "Unnamed aircraft" : "Your aircraft"),
                a.getMakeModel() == null ? "" : a.getMakeModel().trim(),
                hours(a.getHobbsTimeHours()), hours(a.getTimeInServiceHours()),
                due, mine.size() - due.size(), mine.size()));
        }
        return sections;
    }

    // How far through the owner's warning window an item is, so days and
    // hours compare: days/leadDays and hours/leadHours, the smaller wins.
    // Negative = overdue (more negative = more overdue). Research R3.
    static double sortKey(StatusSheetItem t, Double tis, LocalDate today, int leadDays, int leadHours) {
        double key = Double.MAX_VALUE;
        Long days = DueDates.daysUntil(t.getDueDateDate(), today);
        if (days != null) key = Math.min(key, days / (double) Math.max(leadDays, 1));
        Double hrs = DueDates.hoursUntil(t.getDueDateHours(), tis);
        if (hrs != null) key = Math.min(key, hrs / Math.max(leadHours, 1));
        return key;
    }

    // Newest time an overdue item was alerted under the old per-item rule; seeds
    // lastOverdueSentAt once after spec 014 deploys (research R6/R7).
    public Instant latestOverdueAlertAt(User user) {
        return statusSheetItemRepository.findByUserOrderByAircraftIdAscStatusSheetOrderAsc(user).stream()
            .filter(t -> AlertLevel.parse(t.getAlertLevel()) == AlertLevel.OVERDUE && t.getAlertLastFiredAt() != null)
            .map(StatusSheetItem::getAlertLastFiredAt)
            .max(Instant::compareTo)
            .orElse(null);
    }

    // A scheduled status report with nothing due gets its own subject:
    // "N12SAS — weekly status: all on track" (spec 014 FR-010).
    public void markAsStatusReport(AlertDigest digest, List<Aircraft> fleet, int everyDays) {
        if (digest.overdueCount() > 0 || digest.dueSoonCount() > 0) return;
        String tail = fleet.size() > 1
            ? "Your fleet"
            : (fleet.isEmpty() ? "Your aircraft" : AlertText.tailOf(fleet.get(0), "Your aircraft"));
        String when = everyDays == 7 ? "weekly status" : "status every " + everyDays + " days";
        digest.setSubject(tail + " — " + when + ": all on track");
    }

    // -- subject --

    private String buildSubject(List<Aircraft> fleet, int overdue, int dueSoon) {
        String tail = fleet.size() > 1
            ? "Your fleet"
            : (fleet.isEmpty() ? "Your aircraft" : AlertText.tailOf(fleet.get(0), "Your aircraft"));
        if (overdue == 0 && dueSoon == 0) {
            return tail + " — maintenance status";
        }
        List<String> parts = new ArrayList<>();
        if (overdue > 0) parts.add(overdue + " overdue");
        if (dueSoon > 0) parts.add(dueSoon + " due soon");
        return tail + " — " + String.join(", ", parts);
    }

    static String timeLeftText(StatusSheetItem t, Double tis, LocalDate today) {
        //Overdue parts first, then what's left
        // "480.0 hrs overdue 106 days left"
        List<String> overdue = new ArrayList<>();
        List<String> left = new ArrayList<>();
        Long days = DueDates.daysUntil(t.getDueDateDate(), today);
        if (days != null) {
            if(days < 0) overdue.add(Math.abs(days) + " days");
            else left.add(days + " days");
        }
        Double hrs = DueDates.hoursUntil(t.getDueDateHours(), tis);
        if (hrs != null) {
            double rounded = Math.round(hrs * 10.0) / 10.0;
            if (rounded < 0) overdue.add(Math.abs(rounded) + " hrs");
            else left.add(rounded + " hrs");
        }
        List<String> parts = new ArrayList<>();
        if(!overdue.isEmpty()) parts.add(String.join(", ", overdue) + " overdue");
        if(!left.isEmpty()) parts.add(String.join(", ", left) + " left");
        return String.join(" · ", parts);
    }

    private static String hours(Double v) {
        return v == null ? "—" : Formatting.formatHours(v);
    }


    // Plain holder (fields accessed directly below, not record-style).
    private static final class Row {
        final StatusSheetItem row;
        final AlertLevel current;
        final AlertLevel previous;
        final String timeLeft;
        final double sortKey;

        Row(StatusSheetItem row, AlertLevel current, AlertLevel previous, String timeLeft, double sortKey) {
            this.row = row;
            this.current = current;
            this.previous = previous;
            this.timeLeft = timeLeft;
            this.sortKey = sortKey;
        }
    }
}
