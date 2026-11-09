package com.example.AviaryService.services.aircraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.FlightLog;
import com.example.AviaryService.entity.FlightSuggestion;
import com.example.AviaryService.entity.ImportProposal;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.Subscription;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.FlightLogRepository;
import com.example.AviaryService.repositories.FlightSuggestionRepository;
import com.example.AviaryService.repositories.ImportProposalRepository;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.SubscriptionRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.support.TestFleet;

// US1 (add) and US5 (remove) rules for an owner's fleet.
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AircraftServiceTest {

    @Autowired AircraftService aircraftService;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired UserRepository userRepository;
    @Autowired ServiceTimelineRepository timelineRepository;
    @Autowired FlightLogRepository flightLogRepository;
    @Autowired FlightSuggestionRepository suggestionRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired ImportProposalRepository proposalRepository;

    private User owner;
    private Aircraft first;

    @BeforeEach
    void setUp() {
        owner = newUser("fleet-");
        first = TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N482DM", "Pat", 100, 90);
    }

    private User newUser(String prefix) {
        User u = new User();
        u.setUsername(prefix + System.nanoTime());
        u.setPassword("x");
        return userRepository.save(u);
    }

    private String fieldError(Runnable r) {
        AircraftValidationException e = assertThrows(AircraftValidationException.class, r::run);
        assertEquals("tailNumber", e.getField());
        return e.getMessage();
    }

    // ---- create (US1) ----

    @Test
    void createRequiresTailNumber() {
        assertEquals("Enter a tail number.", fieldError(() -> aircraftService.create(owner, "  ", null, null, null)));
    }

    @Test
    void duplicateTailIgnoringCaseAndSpacesIsRejected() {
        assertEquals("You already have an aircraft with this tail number.",
            fieldError(() -> aircraftService.create(owner, "n 482 dm", null, null, null)));
    }

    @Test
    void sameTailInAnotherAccountIsFine() {
        User other = newUser("other-");
        TestFleet.addAircraft(aircraftRepository, userRepository, other);
        Aircraft a = aircraftService.create(other, "N482DM", null, null, null);
        assertEquals("N482DM", a.getTailNumber());
    }

    @Test
    void twentySixthAircraftIsRejected() {
        for (int i = 2; i <= Aircraft.MAX_PER_USER; i++) {
            aircraftService.create(owner, "N" + i + "XX", null, null, null);
        }
        assertEquals(Aircraft.MAX_PER_USER, aircraftRepository.countByUser(owner));
        assertEquals("You can track up to 25 aircraft.",
            fieldError(() -> aircraftService.create(owner, "N999ZZ", null, null, null)));
    }

    @Test
    void newAircraftStartsEmptyAndBecomesTheViewedOne() {
        Aircraft a = aircraftService.create(owner, "n731kr", "Piper PA-28", "", null);
        assertEquals("N731KR", a.getTailNumber());
        assertEquals(0.0, a.getBlockTimeHours());
        assertEquals(0.0, a.getTimeInServiceHours());
        assertTrue(timelineRepository.findByAircraftOrderByTimelineOrderAsc(a).isEmpty());
        assertTrue(flightLogRepository.findByAircraft(a).isEmpty());
        assertEquals(a.getId(), userRepository.findById(owner.getId()).orElseThrow().getLastViewedAircraft().getId());
        // The first aircraft is untouched.
        assertEquals(90.0, aircraftRepository.findById(first.getId()).orElseThrow().getTimeInServiceHours());
    }

    // ---- choosing (US3) ----

    @Test
    void dashboardFallsBackToFirstWhenLastViewedIsGone() {
        Aircraft second = aircraftService.create(owner, "N2BB", null, null, null);
        assertEquals(second.getId(), aircraftService.resolveForDashboard(owner, null).getId());
        assertEquals(first.getId(), aircraftService.resolveForDashboard(owner, first.getId()).getId());
        assertEquals(first.getId(), owner.getLastViewedAircraft().getId());
    }

    @Test
    void anotherAccountsAircraftIsNotFound() {
        User other = newUser("other-");
        Aircraft theirs = TestFleet.addAircraft(aircraftRepository, userRepository, other, "N9", "", 0, 0);
        assertThrows(AircraftNotFoundException.class, () -> aircraftService.resolveForDashboard(owner, theirs.getId()));
        assertEquals(first.getId(), owner.getLastViewedAircraft().getId());
    }

    // ---- remove (US5) ----

    @Test
    void removeNeedsTheTailNumber() {
        aircraftService.create(owner, "N2BB", null, null, null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> aircraftService.remove(owner, first.getId(), "N999"));
        assertEquals("Type the tail number to confirm.", e.getMessage());
        assertTrue(aircraftRepository.findById(first.getId()).isPresent());
    }

    @Test
    void unnamedAircraftIsConfirmedWithDELETE() {
        Aircraft unnamed = aircraftRepository.save(new Aircraft(owner));
        assertThrows(IllegalArgumentException.class, () -> aircraftService.remove(owner, unnamed.getId(), ""));
        aircraftService.remove(owner, unnamed.getId(), "delete");
        assertFalse(aircraftRepository.findById(unnamed.getId()).isPresent());
    }

    @Test
    void lastAircraftCannotBeRemoved() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> aircraftService.remove(owner, first.getId(), "N482DM"));
        assertEquals("You can't remove your only aircraft.", e.getMessage());
    }

    @Test
    void removeDeletesOnlyThatAircraftsRecords() {
        Aircraft second = aircraftService.create(owner, "N2BB", null, null, null);
        ServiceTimeline keep = row(first, "Annual");
        ServiceTimeline gone = row(second, "Oil");
        FlightLog keepLog = flight(first);
        FlightLog goneLog = flight(second);
        Instant now = Instant.now();
        FlightSuggestion s = new FlightSuggestion(owner, "N2BB", "fa-" + System.nanoTime(), now.minusSeconds(60), now, "A", "B");
        s.setAircraft(second);
        suggestionRepository.save(s);
        Subscription sub = new Subscription(owner, "N2BB");
        sub.setAircraft(second);
        subscriptionRepository.save(sub);
        ImportProposal p = new ImportProposal();
        p.setUser(owner);
        p.setAircraft(second);
        p.setStatus(ImportProposal.OPEN);
        p.setItemsJson("[]");
        p.setRejectedJson("[]");
        p.setCreatedAt(now);
        p.setExpiresAt(now.plusSeconds(3600));
        proposalRepository.save(p);

        Aircraft next = aircraftService.remove(owner, second.getId(), " n2 bb ");

        assertEquals(first.getId(), next.getId());
        assertFalse(aircraftRepository.findById(second.getId()).isPresent());
        assertFalse(timelineRepository.findById(gone.getId()).isPresent());
        assertFalse(flightLogRepository.findById(goneLog.getId()).isPresent());
        assertFalse(suggestionRepository.findById(s.getId()).isPresent());
        assertFalse(subscriptionRepository.findById(sub.getId()).isPresent());
        ImportProposal kept = proposalRepository.findById(p.getId()).orElseThrow();
        assertNull(kept.getAircraft());
        assertEquals(ImportProposal.EXPIRED, kept.getStatus());
        // The other aircraft is untouched.
        assertTrue(timelineRepository.findById(keep.getId()).isPresent());
        assertTrue(flightLogRepository.findById(keepLog.getId()).isPresent());
        assertEquals(first.getId(), owner.getLastViewedAircraft().getId());
    }

    private ServiceTimeline row(Aircraft a, String item) {
        ServiceTimeline t = new ServiceTimeline();
        t.setUser(owner);
        t.setAircraft(a);
        t.setItem(item);
        t.setTimelineOrder(0);
        return timelineRepository.save(t);
    }

    private FlightLog flight(Aircraft a) {
        FlightLog f = new FlightLog();
        f.setUser(owner);
        f.setAircraft(a);
        f.setFromAirport("KTMB");
        return flightLogRepository.save(f);
    }
}
