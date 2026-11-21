package com.example.AviaryService.services.dashboard;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.FlightLog;
import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.repositories.FlightLogRepository;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.util.Formatting;

// Keeps one aircraft's hours right (Hobbs time and time in service). Handles
// manual hour edits, catches typed readings that look like typos compared to
// existing logs, and recalculates the hours from the flight logs.
@Service
public class HoursService {

    private final AircraftRepository aircraftRepository;
    private final FlightLogRepository flightLogRepository;

    public HoursService(AircraftRepository aircraftRepository, FlightLogRepository flightLogRepository) {
        this.aircraftRepository = aircraftRepository;
        this.flightLogRepository = flightLogRepository;
    }

    @Transactional
    public void updateHours(Double hobbsTimeToAdd,
        Double timeInServiceToAdd, Double newHobbsTime,
        Double newTimeInService, Aircraft aircraft) {
            
        // NaN/Infinity would poison every Time Left calculation, and a meter
        // reading can't be negative.
        for (Double v : new Double[] { newHobbsTime, newTimeInService }) {
            if (v != null && (!Double.isFinite(v) || v < 0)) {
                throw new IllegalArgumentException("Hours must be a number of 0 or more.");
            }
        }
        for (Double v : new Double[] { hobbsTimeToAdd, timeInServiceToAdd }) {
            if (v != null && !Double.isFinite(v)) {
                throw new IllegalArgumentException("Hours must be a number.");
            }
        }
        // Checked up front so a rejected add never leaves the aircraft half-updated.
        double currentHobbs = aircraft.getHobbsTimeHours() == null ? 0 : aircraft.getHobbsTimeHours();
        double currentService = aircraft.getTimeInServiceHours() == null ? 0 : aircraft.getTimeInServiceHours();
        if ((newHobbsTime == null && hobbsTimeToAdd != null && currentHobbs + hobbsTimeToAdd < 0)
                || (newTimeInService == null && timeInServiceToAdd != null && currentService + timeInServiceToAdd < 0)) {
            throw new IllegalArgumentException("That would make your hours negative.");
        }

        boolean updated = false;

        System.out.println("==========");

        if (newHobbsTime != null) { //If newHobbsTime has a value
            aircraft.setHobbsTimeHours(newHobbsTime);
            // Manual edit also sets the floor — logs can raise this, never lower it.
            aircraft.setHobbsTimeManualBaseline(newHobbsTime);
            System.out.println("Setting HobbsTime time to: " + newHobbsTime);
            updated = true;
        } else if (hobbsTimeToAdd != null) { //else if hobbsTimeToAdd has a value
            double currentHobbsTime = aircraft.getHobbsTimeHours();
            double computedHobbsTime = Formatting.roundHours(currentHobbsTime + hobbsTimeToAdd);
            aircraft.setHobbsTimeHours(computedHobbsTime);
            aircraft.setHobbsTimeManualBaseline(computedHobbsTime);
            System.out.println("Adding " + hobbsTimeToAdd + " to current HobbsTime: " + currentHobbsTime);
            updated = true;
        }

        double finalHobbsTime = aircraft.getHobbsTimeHours();
        System.out.println("Current hobbsTime: " + finalHobbsTime);

        if (newTimeInService != null) { //If newTimeInService has a value
            aircraft.setTimeInServiceHours(newTimeInService);
            aircraft.setTimeInServiceManualBaseline(newTimeInService);
            System.out.println("Setting Time in Service to: " + newTimeInService);
            updated = true;
        } else if (timeInServiceToAdd != null) { //else if timeInServiceToAdd has a value
            double currentTimeInService = aircraft.getTimeInServiceHours();
            double computedTimeInService = Formatting.roundHours(currentTimeInService + timeInServiceToAdd);
            aircraft.setTimeInServiceHours(computedTimeInService);
            aircraft.setTimeInServiceManualBaseline(computedTimeInService);
            System.out.println("Adding " + timeInServiceToAdd + " to current Time in Service: " + currentTimeInService);
            updated = true;
        }

        double finalTimeInService = aircraft.getTimeInServiceHours();
        System.out.println("Current Time in Service: " + finalTimeInService);

        if (!updated) {
            throw new IllegalArgumentException("At least one update parameter must be provided");
        }

        java.time.Instant now = java.time.Instant.now();
        if (newHobbsTime != null || hobbsTimeToAdd != null) {
            aircraft.setHobbsTimeUpdatedAt(now);
            aircraft.setHobbsTimeUpdatedSource("manual");
        }
        if (newTimeInService != null || timeInServiceToAdd != null) {
            aircraft.setTimeInServiceUpdatedAt(now);
            aircraft.setTimeInServiceUpdatedSource("manual");
        }

        aircraftRepository.save(aircraft);

        }

    // A manually-typed reading that contradicts flights already on the
    // books -- e.g. its Out is lower than a reading already recorded before
    // it in time, or its In is higher than one already recorded after it.
    // Either means a typo, not a real gap in flying. Returned to the
    // controller so it can ask the user to confirm before saving anyway.
    public record AccuracyIssue(Double expectedFloor, Double expectedCeiling, Double enteredOut, Double enteredIn) {}

    // Checks a not-yet-saved manual entry against the aircraft's existing logs.
    // Only meaningful when the entry has both a start and end timestamp for
    // this pair -- without those there's nothing to compare chronologically,
    // so it's waved through (same as before this feature existed).
    public AccuracyIssue checkAccuracy(List<FlightLog> existingLogs, Instant candidateStart, Instant candidateEnd,
            Double enteredOut, Double enteredIn, boolean useHobbsTime) {
        if (candidateStart == null || candidateEnd == null || enteredOut == null || enteredIn == null) {
            return null;
        }

        Double floor = null;   // highest known reading at/before candidateStart
        Double ceiling = null; // lowest known reading at/after candidateEnd

        for (FlightLog log : existingLogs) {
            Instant logStart = useHobbsTime ? log.getHobbsTimeStart() : log.getTimeInServiceStart();
            Instant logEnd   = useHobbsTime ? log.getHobbsTimeEnd()   : log.getTimeInServiceEnd();
            Double logOut    = useHobbsTime ? log.getHobbsTimeOut()   : log.getTimeInServiceOut();
            Double logIn     = useHobbsTime ? log.getHobbsTimeIn()    : log.getTimeInServiceIn();
            if (logStart == null || logEnd == null || logOut == null || logIn == null) continue;

            if (!logEnd.isAfter(candidateStart) && (floor == null || logIn > floor)) {
                floor = logIn;
            }
            if (!logStart.isBefore(candidateEnd) && (ceiling == null || logOut < ceiling)) {
                ceiling = logOut;
            }
        }

        boolean outTooLow = floor != null && enteredOut < floor;
        boolean inTooHigh = ceiling != null && enteredIn > ceiling;
        if (!outTooLow && !inTooHigh) {
            return null;
        }
        return new AccuracyIssue(outTooLow ? floor : null, inTooHigh ? ceiling : null, enteredOut, enteredIn);
    }

    /**
     * Walks one aircraft's flight logs in real chronological order (by
     * hobbsTimeStart or timeInServiceStart, whichever pair useHobbsTime
     * selects) and recomputes Out/In for every non-manual row so the chain
     * stays accurate no matter what order flights were entered in.
     *
     * "manual" rows (FlightLog.getEffectiveSource()) and any row missing a
     * timestamp for this pair are treated as fixed anchors: their own
     * Out/In are trusted and never rewritten, and the running total jumps
     * up to match their In reading (never down -- hours only go up).
     * "csv"/"aeroapi" rows have no physical meter behind them, so their
     * Out/In get rewritten to (running total, running total + this
     * flight's own duration), preserving the duration but sliding the
     * absolute numbers to wherever this flight actually falls in time.
     *
     * Returns the final running total, which becomes the aircraft's displayed
     * hobbsTimeHours/timeInServiceHours. Saves every row it changes; does
     * not save the aircraft -- the caller sets the displayed hours field itself.
     */
    @Transactional
    public double recomputeChain(Aircraft aircraft, boolean useHobbsTime) {
        Double baselineBoxed = useHobbsTime ? aircraft.getHobbsTimeManualBaseline() : aircraft.getTimeInServiceManualBaseline();
        if (baselineBoxed == null) {
            baselineBoxed = useHobbsTime ? aircraft.getHobbsTimeHours() : aircraft.getTimeInServiceHours();
            if (useHobbsTime) aircraft.setHobbsTimeManualBaseline(baselineBoxed);
            else aircraft.setTimeInServiceManualBaseline(baselineBoxed);
        }

        List<FlightLog> chain = flightLogRepository.findByAircraft(aircraft).stream()
            .filter(log -> (useHobbsTime ? log.getHobbsTimeOut() : log.getTimeInServiceOut()) != null
                        && (useHobbsTime ? log.getHobbsTimeIn()  : log.getTimeInServiceIn())  != null)
            .sorted(Comparator
                .<FlightLog, Instant>comparing(log -> {
                    Instant start = useHobbsTime ? log.getHobbsTimeStart() : log.getTimeInServiceStart();
                    return start == null ? Instant.MAX : start;
                })
                .thenComparing(FlightLog::getId))
            .toList();

        double running = Formatting.roundHours(baselineBoxed);
        List<FlightLog> toSave = new ArrayList<>();
        for (FlightLog log : chain) {
            Instant start = useHobbsTime ? log.getHobbsTimeStart() : log.getTimeInServiceStart();
            boolean isAnchor = start == null || "manual".equals(log.getEffectiveSource());

            if (isAnchor) {
                double in = useHobbsTime ? log.getHobbsTimeIn() : log.getTimeInServiceIn();
                running = Formatting.roundHours(Math.max(running, in));
            } else {
                double out = useHobbsTime ? log.getHobbsTimeOut() : log.getTimeInServiceOut();
                double in  = useHobbsTime ? log.getHobbsTimeIn()  : log.getTimeInServiceIn();
                // Round every step so error can't accumulate down the chain.
                double duration = Formatting.roundHours(in - out);
                double newOut = Formatting.roundHours(running);
                double newIn  = Formatting.roundHours(running + duration);
                if (useHobbsTime) {
                    log.setHobbsTimeOut(newOut);
                    log.setHobbsTimeIn(newIn);
                } else {
                    log.setTimeInServiceOut(newOut);
                    log.setTimeInServiceIn(newIn);
                }
                running = newIn;
                toSave.add(log);
            }
        }
        if (!toSave.isEmpty()) flightLogRepository.saveAll(toSave);
        return running;
    }

}
