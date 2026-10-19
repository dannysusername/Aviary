package com.example.AviaryService.services.flights;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.FlightLog;
import com.example.AviaryService.entity.FlightSuggestion;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.FlightLogRepository;
import com.example.AviaryService.repositories.FlightSuggestionRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.util.Formatting;
import com.example.AviaryService.services.dashboard.HoursService;

// Accept or dismiss a flight that AeroAPI found for the user. A found flight is
// never added to the logbook on its own; only these two user actions change
// anything. See docs/specs/ADSB_SYNC_SPEC.md, rule 1.
@Service
public class FlightSuggestionService {

    private final FlightSuggestionRepository flightSuggestionRepository;
    private final FlightLogRepository flightLogRepository;
    private final UserRepository userRepository;
    private final HoursService hoursService;

    public FlightSuggestionService(FlightSuggestionRepository flightSuggestionRepository,
            FlightLogRepository flightLogRepository, UserRepository userRepository, HoursService hoursService) {
        this.flightSuggestionRepository = flightSuggestionRepository;
        this.flightLogRepository = flightLogRepository;
        this.userRepository = userRepository;
        this.hoursService = hoursService;
    }

    @Transactional
    public FlightLog accept(long suggestionId, User user) {
        FlightSuggestion suggestion = getOwnedOrThrow(suggestionId, user);

        // Also reachable from the "view all suggested flights" audit list
        // (any status, not just pending) -- guard against creating a second
        // FlightLog for a flight that's already in the log book.
        if (flightLogRepository.findByUserAndFaFlightId(user, suggestion.getFaFlightId()).isPresent()) {
            throw new IllegalArgumentException("This flight is already in your log book.");
        }

        // Block Time can't come from AeroAPI (no engine data) -- stays blank
        // for the user to fill in. Time in Service IS the flight's airborne
        // duration (ADSB_SYNC_SPEC.md, "the real maintenance clock"). Out/In
        // are just seeded with the right duration here -- this suggestion
        // may be older than flights already logged, so
        // HoursService.recomputeChain (below) is what actually places it in
        // the right spot and fixes the absolute numbers.
        double duration = Formatting.roundHours(suggestion.getMinutesAirborne() / 60.0);
        FlightLog flightLog = new FlightLog(
            suggestion.getOrigin(), suggestion.getDestination(),
            null, null, duration, 0.0, user
        );
        flightLog.setTimeInServiceStart(suggestion.getDepartureTime());
        flightLog.setTimeInServiceEnd(suggestion.getArrivalTime());
        flightLog.setFaFlightId(suggestion.getFaFlightId());
        flightLog.setSource("aeroapi");
        flightLogRepository.save(flightLog);

        double newTimeInService = hoursService.recomputeChain(user, /*useBlockTime=*/false);
        user.setTimeInServiceHours(newTimeInService);
        Instant now = Instant.now();
        user.setTimeInServiceUpdatedAt(now);
        user.setTimeInServiceUpdatedSource("flightlog");
        userRepository.save(user);

        // Stays in the table as "accepted" rather than being deleted -- this
        // is what stops the same fa_flight_id from ever being re-suggested.
        suggestion.setStatus("accepted");
        flightSuggestionRepository.save(suggestion);

        return flightLog;
    }

    @Transactional
    public void dismiss(long suggestionId, User user) {
        FlightSuggestion suggestion = getOwnedOrThrow(suggestionId, user);
        suggestion.setStatus("dismissed");
        flightSuggestionRepository.save(suggestion);
    }

    // Permanently removes the suggestion row -- unlike dismiss, this frees up
    // its fa_flight_id, so if AeroAPI reports this flight again it CAN come
    // back as a fresh suggestion. Called from the "view all" audit list.
    @Transactional
    public void delete(long suggestionId, User user) {
        FlightSuggestion suggestion = getOwnedOrThrow(suggestionId, user);
        flightSuggestionRepository.delete(suggestion);
    }

    private FlightSuggestion getOwnedOrThrow(long suggestionId, User user) {
        FlightSuggestion suggestion = flightSuggestionRepository.findById(suggestionId)
            .orElseThrow(() -> new IllegalArgumentException("Flight suggestion not found"));
        if (suggestion.getUser().getId() != user.getId()) {
            throw new IllegalArgumentException("Flight suggestion not found");
        }
        return suggestion;
    }
}
