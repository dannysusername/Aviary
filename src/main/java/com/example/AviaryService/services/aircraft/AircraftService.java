package com.example.AviaryService.services.aircraft;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.ImportProposal;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.FlightLogRepository;
import com.example.AviaryService.repositories.FlightSuggestionRepository;
import com.example.AviaryService.repositories.ImportProposalRepository;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.SubscriptionRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.util.Validation;

// An owner's fleet: add, pick, and remove aircraft. Every lookup is scoped to
// the owner, so another account's aircraft id is "not found".
// See specs/003-multi-aircraft-fleet (data-model.md, contracts/http-endpoints.md).
@Service
public class AircraftService {

    private final AircraftRepository aircraftRepository;
    private final UserRepository userRepository;
    private final ServiceTimelineRepository timelineRepository;
    private final FlightLogRepository flightLogRepository;
    private final FlightSuggestionRepository suggestionRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final ImportProposalRepository proposalRepository;

    public AircraftService(AircraftRepository aircraftRepository, UserRepository userRepository,
            ServiceTimelineRepository timelineRepository, FlightLogRepository flightLogRepository,
            FlightSuggestionRepository suggestionRepository, SubscriptionRepository subscriptionRepository,
            ImportProposalRepository proposalRepository) {
        this.aircraftRepository = aircraftRepository;
        this.userRepository = userRepository;
        this.timelineRepository = timelineRepository;
        this.flightLogRepository = flightLogRepository;
        this.suggestionRepository = suggestionRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.proposalRepository = proposalRepository;
    }

    public List<Aircraft> listFor(User user) {
        return aircraftRepository.findByUserOrderByIdAsc(user);
    }

    // A new, empty aircraft: no rows, no flights, zero hours. Tail number is
    // required here (the info card can add the rest later). Becomes the
    // aircraft the dashboard opens on.
    @Transactional
    public Aircraft create(User user, String tailNumber, String makeModel, String ownerName, String makeModelSN) {
        try {
            Validation.requireValidTailNumber(tailNumber, true);
        } catch (IllegalArgumentException e) {
            throw new AircraftValidationException("tailNumber", e.getMessage());
        }
        checkLength("makeModel", "Make/model", makeModel);
        checkLength("ownerName", "Owner name", ownerName);
        checkLength("makeModelSN", "Serial number", makeModelSN);

        if (aircraftRepository.countByUser(user) >= Aircraft.MAX_PER_USER) {
            throw new AircraftValidationException("tailNumber",
                "You can track up to " + Aircraft.MAX_PER_USER + " aircraft.");
        }
        if (aircraftRepository.existsByUserAndTailNumberKey(user, Validation.tailNumberKey(tailNumber))) {
            throw new AircraftValidationException("tailNumber", "You already have an aircraft with this tail number.");
        }

        Aircraft aircraft = new Aircraft(user);
        aircraft.setTailNumber(tailNumber);
        aircraft.setMakeModel(trimToEmpty(makeModel));
        aircraft.setOwnerName(trimToEmpty(ownerName));
        aircraft.setMakeModelSN(trimToEmpty(makeModelSN));
        aircraft = aircraftRepository.save(aircraft);

        user.setLastViewedAircraft(aircraft);
        userRepository.save(user);
        return aircraft;
    }

    // Which aircraft GET /dashboard shows, per data-model.md "Selected aircraft":
    //   requested + owned -> it (and remember it)
    //   requested + not owned -> AircraftNotFoundException (404), nothing remembered
    //   none -> last viewed if it still exists, else the first aircraft
    // An account with no aircraft at all (backfill missed it) gets an empty
    // one rather than an error page.
    @Transactional
    public Aircraft resolveForDashboard(User user, Long requestedId) {
        Aircraft chosen;
        if (requestedId != null) {
            chosen = aircraftRepository.findByIdAndUser(requestedId, user)
                .orElseThrow(AircraftNotFoundException::new);
        } else {
            Aircraft last = user.getLastViewedAircraft();
            chosen = last == null ? null : aircraftRepository.findByIdAndUser(last.getId(), user).orElse(null);
            if (chosen == null) {
                chosen = aircraftRepository.findFirstByUserOrderByIdAsc(user)
                    .orElseGet(() -> aircraftRepository.save(new Aircraft(user)));
            }
        }
        Aircraft last = user.getLastViewedAircraft();
        if (last == null || !chosen.getId().equals(last.getId())) {
            user.setLastViewedAircraft(chosen);
            userRepository.save(user);
        }
        return chosen;
    }

    // Deletes one aircraft and everything recorded on it, after the owner
    // typed its tail number (or DELETE when it has none). The last aircraft
    // can't be removed. Import history is kept, detached from the aircraft.
    @Transactional
    public Aircraft remove(User user, Long aircraftId, String confirm) {
        Aircraft aircraft = aircraftRepository.findByIdAndUser(aircraftId, user)
            .orElseThrow(AircraftNotFoundException::new);
        if (aircraftRepository.countByUser(user) <= 1) {
            throw new IllegalArgumentException("You can't remove your only aircraft.");
        }
        String expected = aircraft.getTailNumberKey() == null ? "DELETE" : aircraft.getTailNumberKey();
        String typed = Validation.tailNumberKey(confirm);
        if (typed == null || !typed.equals(expected)) {
            throw new IllegalArgumentException("Type the tail number to confirm.");
        }

        for (ImportProposal p : proposalRepository.findByAircraft(aircraft)) {
            p.setAircraft(null);
            if (ImportProposal.OPEN.equals(p.getStatus()) || ImportProposal.NEEDS_FIT_CONFIRMATION.equals(p.getStatus())) {
                p.setStatus(ImportProposal.EXPIRED);
            }
            proposalRepository.save(p);
        }
        suggestionRepository.deleteByAircraft(aircraft);
        subscriptionRepository.deleteByAircraft(aircraft);
        flightLogRepository.deleteByAircraft(aircraft);
        timelineRepository.deleteByAircraft(aircraft);

        Aircraft next = aircraftRepository.findByUserOrderByIdAsc(user).stream()
            .filter(a -> !a.getId().equals(aircraft.getId()))
            .findFirst().orElseThrow();
        Aircraft last = user.getLastViewedAircraft();
        if (last == null || last.getId().equals(aircraft.getId())) {
            user.setLastViewedAircraft(next);
        }
        user.getAircraft().removeIf(a -> a.getId().equals(aircraft.getId()));
        userRepository.save(user);
        aircraftRepository.delete(aircraft);
        return next;
    }

    // How much a remove would delete, for the confirm dialog.
    public long rowCount(Aircraft aircraft) {
        return timelineRepository.countByAircraft(aircraft);
    }

    public long flightCount(Aircraft aircraft) {
        return flightLogRepository.countByAircraft(aircraft);
    }

    private static void checkLength(String field, String label, String value) {
        try {
            Validation.maxLength(label, value);
        } catch (IllegalArgumentException e) {
            throw new AircraftValidationException(field, e.getMessage());
        }
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
