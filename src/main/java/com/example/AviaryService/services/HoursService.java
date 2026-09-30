package com.example.AviaryService.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.FlightLog;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.FlightLogRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.util.Formatting;

@Service
public class HoursService {

    private final UserRepository userRepository;
    private final FlightLogRepository flightLogRepository;

    public HoursService(UserRepository userRepository, FlightLogRepository flightLogRepository) {
        this.userRepository = userRepository;
        this.flightLogRepository = flightLogRepository;
    }

    @Transactional
    public void updateHours(Double blockTimeToAdd,
        Double timeInServiceToAdd, Double newBlockTime,
        Double newTimeInService, User user) {
            
        // NaN/Infinity would poison every Time Left calculation, and a meter
        // reading can't be negative.
        for (Double v : new Double[] { newBlockTime, newTimeInService }) {
            if (v != null && (!Double.isFinite(v) || v < 0)) {
                throw new IllegalArgumentException("Hours must be a number of 0 or more.");
            }
        }
        for (Double v : new Double[] { blockTimeToAdd, timeInServiceToAdd }) {
            if (v != null && !Double.isFinite(v)) {
                throw new IllegalArgumentException("Hours must be a number.");
            }
        }
        // Checked up front so a rejected add never leaves the user half-updated.
        double currentBlock = user.getBlockTimeHours() == null ? 0 : user.getBlockTimeHours();
        double currentService = user.getTimeInServiceHours() == null ? 0 : user.getTimeInServiceHours();
        if ((newBlockTime == null && blockTimeToAdd != null && currentBlock + blockTimeToAdd < 0)
                || (newTimeInService == null && timeInServiceToAdd != null && currentService + timeInServiceToAdd < 0)) {
            throw new IllegalArgumentException("That would make your hours negative.");
        }

        boolean updated = false;

        System.out.println("==========");

        if (newBlockTime != null) { //If newBlockTime has a value
            user.setBlockTimeHours(newBlockTime);
            // Manual edit also sets the floor — logs can raise this, never lower it.
            user.setBlockTimeManualBaseline(newBlockTime);
            System.out.println("Setting BlockTime time to: " + newBlockTime);
            updated = true;
        } else if (blockTimeToAdd != null) { //else if blockTimeToAdd has a value
            double currentBlockTime = user.getBlockTimeHours();
            double computedBlockTime = Formatting.roundHours(currentBlockTime + blockTimeToAdd);
            user.setBlockTimeHours(computedBlockTime);
            user.setBlockTimeManualBaseline(computedBlockTime);
            System.out.println("Adding " + blockTimeToAdd + " to current BlockTime: " + currentBlockTime);
            updated = true;
        }

        double finalBlockTime = user.getBlockTimeHours();
        System.out.println("Current blockTime: " + finalBlockTime);

        if (newTimeInService != null) { //If newTimeInService has a value
            user.setTimeInServiceHours(newTimeInService);
            user.setTimeInServiceManualBaseline(newTimeInService);
            System.out.println("Setting Time in Service to: " + newTimeInService);
            updated = true;
        } else if (timeInServiceToAdd != null) { //else if timeInServiceToAdd has a value
            double currentTimeInService = user.getTimeInServiceHours();
            double computedTimeInService = Formatting.roundHours(currentTimeInService + timeInServiceToAdd);
            user.setTimeInServiceHours(computedTimeInService);
            user.setTimeInServiceManualBaseline(computedTimeInService);
            System.out.println("Adding " + timeInServiceToAdd + " to current Time in Service: " + currentTimeInService);
            updated = true;
        }

        double finalTimeInService = user.getTimeInServiceHours();
        System.out.println("Current Time in Service: " + finalTimeInService);

        if (!updated) {
            throw new IllegalArgumentException("At least one update parameter must be provided");
        }

        java.time.Instant now = java.time.Instant.now();
        if (newBlockTime != null || blockTimeToAdd != null) {
            user.setBlockTimeUpdatedAt(now);
            user.setBlockTimeUpdatedSource("manual");
        }
        if (newTimeInService != null || timeInServiceToAdd != null) {
            user.setTimeInServiceUpdatedAt(now);
            user.setTimeInServiceUpdatedSource("manual");
        }

        userRepository.save(user);

        }

    // A manually-typed reading that contradicts flights already on the
    // books -- e.g. its Out is lower than a reading already recorded before
    // it in time, or its In is higher than one already recorded after it.
    // Either means a typo, not a real gap in flying. Returned to the
    // controller so it can ask the user to confirm before saving anyway.
    public record AccuracyIssue(Double expectedFloor, Double expectedCeiling, Double enteredOut, Double enteredIn) {}

    // Checks a not-yet-saved manual entry against the user's existing logs.
    // Only meaningful when the entry has both a start and end timestamp for
    // this pair -- without those there's nothing to compare chronologically,
    // so it's waved through (same as before this feature existed).
    public AccuracyIssue checkAccuracy(List<FlightLog> existingLogs, Instant candidateStart, Instant candidateEnd,
            Double enteredOut, Double enteredIn, boolean useBlockTime) {
        if (candidateStart == null || candidateEnd == null || enteredOut == null || enteredIn == null) {
            return null;
        }

        Double floor = null;   // highest known reading at/before candidateStart
        Double ceiling = null; // lowest known reading at/after candidateEnd

        for (FlightLog log : existingLogs) {
            Instant logStart = useBlockTime ? log.getBlockTimeStart() : log.getTimeInServiceStart();
            Instant logEnd   = useBlockTime ? log.getBlockTimeEnd()   : log.getTimeInServiceEnd();
            Double logOut    = useBlockTime ? log.getBlockTimeOut()   : log.getTimeInServiceOut();
            Double logIn     = useBlockTime ? log.getBlockTimeIn()    : log.getTimeInServiceIn();
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
     * Walks a user's flight logs in real chronological order (by
     * blockTimeStart or timeInServiceStart, whichever pair useBlockTime
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
     * Returns the final running total, which becomes the user's displayed
     * blockTimeHours/timeInServiceHours. Saves every row it changes; does
     * not save the user -- the caller sets the displayed hours field itself.
     */
    @Transactional
    public double recomputeChain(User user, boolean useBlockTime) {
        Double baselineBoxed = useBlockTime ? user.getBlockTimeManualBaseline() : user.getTimeInServiceManualBaseline();
        if (baselineBoxed == null) {
            baselineBoxed = useBlockTime ? user.getBlockTimeHours() : user.getTimeInServiceHours();
            if (useBlockTime) user.setBlockTimeManualBaseline(baselineBoxed);
            else user.setTimeInServiceManualBaseline(baselineBoxed);
        }

        List<FlightLog> chain = flightLogRepository.findByUser(user).stream()
            .filter(log -> (useBlockTime ? log.getBlockTimeOut() : log.getTimeInServiceOut()) != null
                        && (useBlockTime ? log.getBlockTimeIn()  : log.getTimeInServiceIn())  != null)
            .sorted(Comparator
                .<FlightLog, Instant>comparing(log -> {
                    Instant start = useBlockTime ? log.getBlockTimeStart() : log.getTimeInServiceStart();
                    return start == null ? Instant.MAX : start;
                })
                .thenComparing(FlightLog::getId))
            .toList();

        double running = Formatting.roundHours(baselineBoxed);
        List<FlightLog> toSave = new ArrayList<>();
        for (FlightLog log : chain) {
            Instant start = useBlockTime ? log.getBlockTimeStart() : log.getTimeInServiceStart();
            boolean isAnchor = start == null || "manual".equals(log.getEffectiveSource());

            if (isAnchor) {
                double in = useBlockTime ? log.getBlockTimeIn() : log.getTimeInServiceIn();
                running = Formatting.roundHours(Math.max(running, in));
            } else {
                double out = useBlockTime ? log.getBlockTimeOut() : log.getTimeInServiceOut();
                double in  = useBlockTime ? log.getBlockTimeIn()  : log.getTimeInServiceIn();
                // Round every step so error can't accumulate down the chain.
                double duration = Formatting.roundHours(in - out);
                double newOut = Formatting.roundHours(running);
                double newIn  = Formatting.roundHours(running + duration);
                if (useBlockTime) {
                    log.setBlockTimeOut(newOut);
                    log.setBlockTimeIn(newIn);
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
