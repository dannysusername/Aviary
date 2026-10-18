package com.example.AviaryService.services.alerts;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.AviaryService.entity.AlertPreference;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.util.DueDates;
import com.example.AviaryService.util.Formatting;

// Decides what goes in a user's alert message. Checks each maintenance item
// against the user's alert settings and writes the subject and body.
//   buildDigest(...) -- only reads; decides if anything should be sent
//   markFired(...)   -- remembers what was alerted, so an item isn't alerted
//                       again unless it gets worse
// See docs/specs/ALERTS_SPEC.md ("How it fires", "Digest content").
@Service
public class AlertDigestService {

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a 'UTC'").withZone(ZoneOffset.UTC);

    private final ServiceTimelineRepository serviceTimelineRepository;

    public AlertDigestService(ServiceTimelineRepository serviceTimelineRepository) {
        this.serviceTimelineRepository = serviceTimelineRepository;
    }

    public AlertDigest buildDigest(User user, AlertPreference prefs, boolean baseline) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Double tis = user.getTimeInServiceHours();

        List<ServiceTimeline> rows = serviceTimelineRepository.findByUserOrderByTimelineOrderAsc(user);

        List<Row> evaluated = new ArrayList<>();
        for (ServiceTimeline t : rows) {
            if (t.getIsTitle()) {
                continue;
            }
            AlertLevel current = DueDates.classify(
                t.getDueDateDate(), t.getDueDateHours(), tis, today,
                prefs.getLeadTimeDays(), prefs.getLeadTimeHours());
            AlertLevel previous = AlertLevel.parse(t.getAlertLevel());
            evaluated.add(new Row(t, current, previous, timeLeftText(t, tis, today)));
        }

        int overdue = (int) evaluated.stream().filter(r -> r.current == AlertLevel.OVERDUE).count();
        int dueSoon = (int) evaluated.stream().filter(r -> r.current == AlertLevel.DUE_SOON).count();

        List<Row> changed = evaluated.stream().filter(r -> r.current.worseThan(r.previous)).toList();

        boolean renudgeDue = evaluated.stream().anyMatch(r ->
            r.current == AlertLevel.OVERDUE
                && r.row.getAlertLastFiredAt() != null
                && ChronoUnit.DAYS.between(r.row.getAlertLastFiredAt(), Instant.now()) >= prefs.getOverdueRenudgeDays());

        boolean shouldSend = baseline || !changed.isEmpty() || renudgeDue;

        String subject = buildSubject(user, overdue, dueSoon);
        String body = buildBody(user, evaluated, changed, baseline, overdue, dueSoon);

        return new AlertDigest(shouldSend, subject, body, overdue, dueSoon);
    }

    // Persist current levels so the next run only fires on a change. Called after
    // a successful send and for the baseline.
    public void markFired(User user, AlertPreference prefs) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Double tis = user.getTimeInServiceHours();
        Instant now = Instant.now();

        List<ServiceTimeline> rows = serviceTimelineRepository.findByUserOrderByTimelineOrderAsc(user);
        for (ServiceTimeline t : rows) {
            if (t.getIsTitle()) {
                continue;
            }
            AlertLevel current = DueDates.classify(
                t.getDueDateDate(), t.getDueDateHours(), tis, today,
                prefs.getLeadTimeDays(), prefs.getLeadTimeHours());
            t.setAlertLevel(current.stored());
            if (current != AlertLevel.OK) {
                t.setAlertLastFiredAt(now);
            }
        }
        serviceTimelineRepository.saveAll(rows);
    }

    // -- rendering --

    private String buildSubject(User user, int overdue, int dueSoon) {
        String tail = user.getTailNumber() == null || user.getTailNumber().isBlank()
            ? "Your aircraft" : user.getTailNumber();
        if (overdue == 0 && dueSoon == 0) {
            return tail + " — maintenance status";
        }
        List<String> parts = new ArrayList<>();
        if (overdue > 0) parts.add(overdue + " overdue");
        if (dueSoon > 0) parts.add(dueSoon + " due soon");
        return tail + " — " + String.join(", ", parts);
    }

    private String buildBody(User user, List<Row> evaluated, List<Row> changed,
                             boolean baseline, int overdue, int dueSoon) {
        StringBuilder sb = new StringBuilder();

        String tail = AlertText.tail(user);
        String makeModel = orDash(user.getMakeModel());
        sb.append("Maintenance status for ").append(tail);
        if (!"—".equals(makeModel)) {
            sb.append(" (").append(makeModel).append(')');
        }
        sb.append("\nas of ").append(STAMP.format(Instant.now())).append("\n");

        if (baseline) {
            sb.append("\nAlerts are now ON. This is your starting picture — from here you'll only\n");
            sb.append("get an email when something changes or an overdue item needs another nudge.\n");
        } else if (!changed.isEmpty()) {
            sb.append("\nWhat changed:\n");
            for (Row r : changed) {
                sb.append("  • ").append(label(r.current)).append("  ")
                  .append(r.row.getItem()).append(" — ").append(r.timeLeft).append('\n');
            }
        }

        sb.append("\nFull status (").append(overdue).append(" overdue, ")
          .append(dueSoon).append(" due soon):\n");
        List<Row> sorted = new ArrayList<>(evaluated);
        sorted.sort(Comparator.comparingInt((Row r) -> -r.current.ordinal()));
        if (sorted.isEmpty()) {
            sb.append("  (no maintenance items)\n");
        }
        for (Row r : sorted) {
            sb.append("  ").append(String.format("%-9s", label(r.current))).append(' ')
              .append(r.row.getItem());
            if (r.timeLeft != null && !r.timeLeft.isBlank() && !"N/A".equals(r.timeLeft)) {
                sb.append(" — ").append(r.timeLeft.replace('\n', ' '));
            }
            sb.append('\n');
        }

        sb.append("\nAircraft hours: Block Time ").append(hours(user.getBlockTimeHours()))
          .append(" · Time in Service ").append(hours(user.getTimeInServiceHours())).append('\n');

        return sb.toString();
    }

    private static String label(AlertLevel level) {
        return switch (level) {
            case OVERDUE -> "OVERDUE";
            case DUE_SOON -> "DUE SOON";
            case OK -> "ok";
        };
    }

    static String timeLeftText(ServiceTimeline t, Double tis, LocalDate today) {
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

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    // Plain holder (fields accessed directly below, not record-style).
    private static final class Row {
        final ServiceTimeline row;
        final AlertLevel current;
        final AlertLevel previous;
        final String timeLeft;

        Row(ServiceTimeline row, AlertLevel current, AlertLevel previous, String timeLeft) {
            this.row = row;
            this.current = current;
            this.previous = previous;
            this.timeLeft = timeLeft;
        }
    }
}
