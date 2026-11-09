package com.example.AviaryService.services.flights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.Subscription;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.FlightSuggestionRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.support.TestFleet;

// US6 / FR-019: flight sync is per aircraft. Suggestions found for one
// aircraft's tail land on that aircraft, and accepting one raises only its hours.
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FlightSyncServiceTest {

    @Autowired FlightSyncService syncService;
    @Autowired SubscriptionService subscriptionService;
    @Autowired FlightSuggestionService suggestionService;
    @Autowired FlightSuggestionRepository suggestionRepository;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired UserRepository userRepository;
    @MockitoBean AeroApiClient aeroApiClient;

    private User owner;
    private Aircraft a;
    private Aircraft b;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setUsername("sync-" + System.nanoTime());
        owner.setPassword("x");
        owner.setAeroApiKey("test-key");
        userRepository.save(owner);
        a = TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N1AA", "", 0, 100);
        b = TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N2BB", "", 0, 500);
    }

    @Test
    void eachAircraftHasItsOwnSubscription() {
        Subscription sa = subscriptionService.subscribe(owner, a, "N1AA");
        Subscription sb = subscriptionService.subscribe(owner, b, "N2BB");
        assertEquals(a.getId(), sa.getAircraft().getId());
        assertEquals(b.getId(), sb.getAircraft().getId());
    }

    @Test
    void suggestionsLandOnTheSubscribedAircraft() {
        Instant off = Instant.parse("2026-11-01T15:00:00Z");
        when(aeroApiClient.getRecentFlights(eq("N2BB"), anyString(), any(), any())).thenReturn(List.of(
            new AeroApiClient.AeroApiFlight("fa-b-" + System.nanoTime(), off, off.plusSeconds(3600), "KFXE", "KBCT", "Arrived")));
        Subscription sb = subscriptionService.subscribe(owner, b, "N2BB");

        assertEquals(1, syncService.syncNow(sb));
        assertEquals(1, suggestionRepository.findByAircraftAndStatus(b, "pending").size());
        assertTrue(suggestionRepository.findByAircraftAndStatus(a, "pending").isEmpty());

        long id = suggestionRepository.findByAircraftAndStatus(b, "pending").get(0).getId();
        suggestionService.accept(id, owner);
        assertEquals(501.0, aircraftRepository.findById(b.getId()).orElseThrow().getTimeInServiceHours(), 1e-9);
        assertEquals(100.0, aircraftRepository.findById(a.getId()).orElseThrow().getTimeInServiceHours(), 1e-9);
    }
}
