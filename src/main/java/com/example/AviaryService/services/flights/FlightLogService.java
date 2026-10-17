package com.example.AviaryService.services.flights;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.FlightLog;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.FlightLogRepository;
import com.example.AviaryService.repositories.FlightSuggestionRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.util.Formatting;
import com.example.AviaryService.util.Validation;
import com.example.AviaryService.services.dashboard.HoursService;

// The rules for flight logs. Kept here so the web controller and the future
// MCP tools both follow the same rules.
@Service
public class FlightLogService {

    private final FlightLogRepository flightLogRepository;
    private final FlightSuggestionRepository flightSuggestionRepository;
    private final UserRepository userRepository;
    private final HoursService hoursService;

    public FlightLogService(FlightLogRepository flightLogRepository,
            FlightSuggestionRepository flightSuggestionRepository,
            UserRepository userRepository, HoursService hoursService) {
        this.flightLogRepository = flightLogRepository;
        this.flightSuggestionRepository = flightSuggestionRepository;
        this.userRepository = userRepository;
        this.hoursService = hoursService;
    }

    // The user's Block Time and Time in Service totals after a log change.
    public record HourTotals(double blockTime, double timeInService) {}

    // A saved log plus the totals it produced.
    public record AddedFlightLog(FlightLog log, HourTotals totals) {}

    // A manually-typed reading that doesn't line up with the user's other
    // flight logs -- see HoursService.checkAccuracy. Not a hard error: the
    // caller can ask the user "Add it anyway?" and retry with force=true.
    public static class InaccurateEntryException extends RuntimeException {
        public InaccurateEntryException(String message) {
            super(message);
        }
    }

    // All of this user's flight logs, in chronological order.
    public List<FlightLog> listFor(User user) {
        return sortedByFlightTime(flightLogRepository.findByUser(user));
    }

    // Validates and saves a new log, then recomputes the user's hours.
    // Throws IllegalArgumentException for bad input, and
    // InaccurateEntryException (unless force) when a manual reading doesn't
    // fit the user's other flights.
    @Transactional
    public AddedFlightLog add(User user, FlightLog newLog, boolean force) {
        // Clamp readings to hundredths. Real meters never exceed 2dp (Hobbs
        // 0.1, Tach 0.01), and this keeps CSV-prefilled or client-computed
        // values from carrying float artifacts into storage.
        newLog.setBlockTimeOut(Formatting.roundHoursOrNull(newLog.getBlockTimeOut()));
        newLog.setBlockTimeIn(Formatting.roundHoursOrNull(newLog.getBlockTimeIn()));
        newLog.setTimeInServiceOut(Formatting.roundHoursOrNull(newLog.getTimeInServiceOut()));
        newLog.setTimeInServiceIn(Formatting.roundHoursOrNull(newLog.getTimeInServiceIn()));

        if (tooLong(newLog.getFromAirport()) || tooLong(newLog.getToAirport())) {
            throw new IllegalArgumentException(
                "Airport must be " + Validation.MAX_TEXT + " characters or fewer.");
        }

        // ── Validation ─────────────────────────────────────────────────────────
        // Reject incomplete entries before they can corrupt displayed hours.
        // Rule: must provide at least one COMPLETE pair (blockTimeOut+blockTimeIn or
        // timeInServiceOut+timeInServiceIn). Partial pairs (e.g. only timeInServiceOut) are the exact case
        // that used to silently wipe the user's manual hours to zero.
        Double ho = newLog.getBlockTimeOut(), hi = newLog.getBlockTimeIn();
        Double to = newLog.getTimeInServiceOut(), ti = newLog.getTimeInServiceIn();
        boolean blockTimePair = (ho != null && hi != null);
        boolean timeInServicePair  = (to != null && ti != null);
        boolean blockTimePartial = (ho == null) != (hi == null);  // exactly one set
        boolean timeInServicePartial  = (to == null) != (ti == null);

        if (!blockTimePair && !timeInServicePair) {
            throw new IllegalArgumentException(
                "Enter both Block Time Out and Block Time In, or both Time in Service Out and Time in Service In.");
        }
        if (blockTimePartial) {
            throw new IllegalArgumentException(
                "Block time entry is incomplete — enter both Block Time Out and Block Time In.");
        }
        if (timeInServicePartial) {
            throw new IllegalArgumentException(
                "Time in Service entry is incomplete — enter both Time in Service Out and Time in Service In.");
        }
        if (blockTimePair && (ho < 0 || hi < 0 || hi < ho)) {
            throw new IllegalArgumentException(
                "Block Time In must be ≥ Block Time Out, and values cannot be negative.");
        }
        if (timeInServicePair && (to < 0 || ti < 0 || ti < to)) {
            throw new IllegalArgumentException(
                "Time in Service In must be ≥ Time in Service Out, and values cannot be negative.");
        }

        // A CSV-prefilled or manually-typed entry -- decides whether
        // HoursService.recomputeChain treats this row's Out/In as a fixed
        // anchor ("manual") or something it can rewrite to fit
        // chronologically ("csv"). AeroAPI rows are never created here --
        // see FlightSuggestionService.accept().
        String source = newLog.getSource();
        if (source == null || source.isBlank()) source = "manual";
        newLog.setSource(source);

        // Only a manually-typed reading gets sanity-checked against nearby
        // flights -- see HoursService.checkAccuracy. A CSV/AeroAPI reading
        // has no physical meter behind it, so there's nothing to be
        // "inaccurate": it just gets slotted into the chain below.
        if ("manual".equals(source) && !force) {
            List<FlightLog> existingLogs = flightLogRepository.findByUser(user);
            HoursService.AccuracyIssue blockIssue = blockTimePair
                ? hoursService.checkAccuracy(existingLogs, newLog.getBlockTimeStart(), newLog.getBlockTimeEnd(), ho, hi, true)
                : null;
            HoursService.AccuracyIssue serviceIssue = timeInServicePair
                ? hoursService.checkAccuracy(existingLogs, newLog.getTimeInServiceStart(), newLog.getTimeInServiceEnd(), to, ti, false)
                : null;
            if (blockIssue != null || serviceIssue != null) {
                throw new InaccurateEntryException(inaccuracyMessage(blockIssue, serviceIssue));
            }
        }

        // Force user ownership server-side regardless of what the client sent.
        newLog.setUser(user);
        FlightLog savedLog = flightLogRepository.save(newLog);
        return new AddedFlightLog(savedLog, recomputeHours(user));
    }

    // Deletes one of the user's logs and recomputes their hours. Throws
    // IllegalArgumentException if it doesn't exist, SecurityException if
    // it belongs to someone else.
    @Transactional
    public HourTotals delete(User user, Long id) {
        FlightLog log = flightLogRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Flight log not found"));
        if (!log.getUser().equals(user)) {
            throw new SecurityException("You do not own this flight log");
        }

        // This log came from an accepted AeroAPI suggestion -- deleting it
        // should give the flight back, not lose it forever. Otherwise the
        // suggestion stays "accepted" with nothing pointing at it, and
        // its fa_flight_id permanently blocks re-suggesting that flight.
        if (log.getFaFlightId() != null) {
            flightSuggestionRepository.findByUserAndFaFlightId(user, log.getFaFlightId())
                .ifPresent(suggestion -> {
                    suggestion.setStatus("pending");
                    flightSuggestionRepository.save(suggestion);
                });
        }

        flightLogRepository.delete(log);
        return recomputeHours(user);
    }

    // Re-derives both hour totals from the log chain and stamps them as
    // coming from the flight log.
    private HourTotals recomputeHours(User user) {
        double newBlockTime = hoursService.recomputeChain(user, /*useBlockTime=*/true);
        double newTimeInService  = hoursService.recomputeChain(user, /*useBlockTime=*/false);
        user.setBlockTimeHours(newBlockTime);
        user.setTimeInServiceHours(newTimeInService);
        Instant flightNow = Instant.now();
        user.setBlockTimeUpdatedAt(flightNow);
        user.setBlockTimeUpdatedSource("flightlog");
        user.setTimeInServiceUpdatedAt(flightNow);
        user.setTimeInServiceUpdatedSource("flightlog");
        userRepository.save(user);
        return new HourTotals(newBlockTime, newTimeInService);
    }

    // Chronological order for display: prefer blockTimeStart (engine start),
    // fall back to timeInServiceStart (wheels-off) when only that's known.
    // Rows with neither (legacy entries, or a manual entry saved without
    // dates) sort last, in insertion order among themselves.
    private static List<FlightLog> sortedByFlightTime(List<FlightLog> logs) {
        return logs.stream()
            .sorted(Comparator
                .<FlightLog, Instant>comparing(log -> {
                    Instant t = log.getBlockTimeStart() != null ? log.getBlockTimeStart() : log.getTimeInServiceStart();
                    return t == null ? Instant.MAX : t;
                })
                .thenComparing(FlightLog::getId))
            .collect(Collectors.toList());
    }

    private static boolean tooLong(String value) {
        return value != null && value.length() > Validation.MAX_TEXT;
    }

    private static String inaccuracyMessage(HoursService.AccuracyIssue blockIssue, HoursService.AccuracyIssue serviceIssue) {
        List<String> sentences = new ArrayList<>();
        if (blockIssue != null) sentences.add(describeAccuracyIssue("Block Time", blockIssue));
        if (serviceIssue != null) sentences.add(describeAccuracyIssue("Time in Service", serviceIssue));
        return "This entry doesn't match your other flight logs. "
            + String.join(" ", sentences) + " Add it anyway?";
    }

    private static String describeAccuracyIssue(String label, HoursService.AccuracyIssue issue) {
        Double floor = issue.expectedFloor();
        Double ceiling = issue.expectedCeiling();
        if (floor != null && ceiling != null) {
            return label + ": based on nearby flights, Out should be at least " + round2(floor)
                + " and In should be no more than " + round2(ceiling)
                + " (you entered Out: " + round2(issue.enteredOut()) + ", In: " + round2(issue.enteredIn()) + ").";
        } else if (floor != null) {
            return label + ": based on nearby flights, Out should be at least " + round2(floor)
                + " (you entered " + round2(issue.enteredOut()) + ").";
        } else {
            return label + ": based on nearby flights, In should be no more than " + round2(ceiling)
                + " (you entered " + round2(issue.enteredIn()) + ").";
        }
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
