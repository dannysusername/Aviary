package com.example.AviaryService.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.services.imports.TimelineImportService.ImportResult;
import com.example.AviaryService.support.TestFleet;
import com.example.AviaryService.util.TimelineImportRules.InputItem;

// US6 / FR-020: AI import targets one named aircraft. See contracts/mcp-tools.md.
// Not @Transactional: confirm runs its own transaction. Fresh users per test.
@SpringBootTest
@ActiveProfiles("test")
class AircraftToolsTest {

    @Autowired AircraftTools aircraftTools;
    @Autowired TimelineImportTools importTools;
    @Autowired FlightTools flightTools;
    @Autowired UserRepository userRepository;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired ServiceTimelineRepository timelineRepository;

    private User pilot;
    private Aircraft sr22;
    private Aircraft pa28;

    @BeforeEach
    void setUp() {
        pilot = newUser("fleet-tools-");
        sr22 = TestFleet.addAircraft(aircraftRepository, userRepository, pilot, "N482DM", "", 0, 1400);
        sr22.setMakeModel("Cirrus SR22");
        aircraftRepository.save(sr22);
        pa28 = TestFleet.addAircraft(aircraftRepository, userRepository, pilot, "N731KR", "", 0, 4100);
        pa28.setMakeModel("Piper PA-28");
        aircraftRepository.save(pa28);
    }

    private User newUser(String prefix) {
        User u = new User();
        u.setUsername(prefix + System.nanoTime());
        u.setPassword("x");
        return userRepository.save(u);
    }

    private static List<InputItem> items(String name) {
        return List.of(new InputItem(name, null, null, null, "450", null, null, "50", null, null, null, name + " row", null));
    }

    private void existing(Aircraft a, String name) {
        ServiceTimeline t = new ServiceTimeline();
        t.setUser(pilot);
        t.setAircraft(a);
        t.setItem(name);
        t.setTimelineOrder(0);
        timelineRepository.save(t);
    }

    @Test
    void listAircraftShowsTheFleet() {
        existing(pa28, "Annual");
        List<AircraftTools.AircraftSummary> fleet = McpTestSupport.as(pilot.getUsername(), () -> aircraftTools.listAircraft());
        assertEquals(2, fleet.size());
        assertEquals("N482DM", fleet.get(0).tailNumber());
        assertEquals("Piper PA-28", fleet.get(1).makeModel());
        assertEquals(1, fleet.get(1).timelineItems());
        assertEquals(4100.0, fleet.get(1).timeInServiceHours());
    }

    @Test
    void proposeWithoutAircraftOnAFleetAsksWhichOne() {
        ImportResult r = McpTestSupport.as(pilot.getUsername(), () -> importTools.proposeTimelineImport(items("Oil"), null, null));
        assertEquals("CHOOSE_AIRCRAFT", r.status());
        assertEquals("This account has 2 aircraft. Say which one: N482DM (Cirrus SR22), N731KR (Piper PA-28).", r.message());
    }

    @Test
    void proposeByTailThenConfirmLandsOnlyOnThatAircraft() {
        ImportResult proposed = McpTestSupport.as(pilot.getUsername(),
            () -> importTools.proposeTimelineImport(items("Oil"), null, "n731kr"));
        assertEquals("PROPOSED", proposed.status());
        assertTrue(proposed.summary().startsWith("Importing into N731KR"), proposed.summary());

        ImportResult imported = McpTestSupport.as(pilot.getUsername(),
            () -> importTools.confirmTimelineImport(proposed.proposalId(), null));
        assertEquals("IMPORTED", imported.status());
        assertEquals(1, timelineRepository.findByAircraftOrderByTimelineOrderAsc(pa28).size());
        assertTrue(timelineRepository.findByAircraftOrderByTimelineOrderAsc(sr22).isEmpty());
    }

    @Test
    void duplicatesAreCheckedAgainstTheTargetAircraftOnly() {
        existing(sr22, "Oil");
        ImportResult onPa28 = McpTestSupport.as(pilot.getUsername(),
            () -> importTools.proposeTimelineImport(items("Oil"), null, String.valueOf(pa28.getId())));
        assertTrue(onPa28.items().get(0).flags().isEmpty(), String.valueOf(onPa28.items().get(0).flags()));
        ImportResult onSr22 = McpTestSupport.as(pilot.getUsername(),
            () -> importTools.proposeTimelineImport(items("Oil"), null, "N482DM"));
        assertTrue(onSr22.items().get(0).flags().toString().contains("DUPLICATE_EXISTING"));
    }

    @Test
    void aProposalForOneAircraftDoesNotReplaceAnotherAircraftsProposal() {
        ImportResult forSr22 = McpTestSupport.as(pilot.getUsername(),
            () -> importTools.proposeTimelineImport(items("Oil"), null, "N482DM"));
        McpTestSupport.as(pilot.getUsername(), () -> importTools.proposeTimelineImport(items("Oil"), null, "N731KR"));
        assertEquals("IMPORTED", McpTestSupport.as(pilot.getUsername(),
            () -> importTools.confirmTimelineImport(forSr22.proposalId(), null)).status());
    }

    @Test
    void oneAircraftAccountNeedsNoAircraftArgument() {
        User solo = newUser("solo-");
        TestFleet.addAircraft(aircraftRepository, userRepository, solo, "N1SO", "", 0, 0);
        assertEquals("PROPOSED", McpTestSupport.as(solo.getUsername(),
            () -> importTools.proposeTimelineImport(items("Oil"), null, null)).status());
        assertEquals(0, McpTestSupport.as(solo.getUsername(), () -> flightTools.listFlights(null)).size());
    }

    @Test
    void anotherAccountsAircraftIsNotFound() {
        User other = newUser("other-");
        TestFleet.addAircraft(aircraftRepository, userRepository, other, "N1OT", "", 0, 0);
        ImportResult byId = McpTestSupport.as(other.getUsername(),
            () -> importTools.proposeTimelineImport(items("Oil"), null, String.valueOf(pa28.getId())));
        assertEquals("CHOOSE_AIRCRAFT", byId.status());
        assertTrue(byId.message().startsWith("Aircraft \"" + pa28.getId() + "\" not found. Your aircraft: N1OT"), byId.message());
        assertThrows(IllegalArgumentException.class, () -> McpTestSupport.as(other.getUsername(),
            () -> importTools.listServiceTimeline("N731KR")));
    }

    @Test
    void confirmAfterTheAircraftWasRemovedSaysSo() {
        ImportResult proposed = McpTestSupport.as(pilot.getUsername(),
            () -> importTools.proposeTimelineImport(items("Oil"), null, "N731KR"));
        // Simulate removal: the proposal loses its aircraft but stays open.
        com.example.AviaryService.entity.ImportProposal p = proposalRepository.findById(proposed.proposalId()).orElseThrow();
        p.setAircraft(null);
        proposalRepository.save(p);
        ImportResult r = McpTestSupport.as(pilot.getUsername(),
            () -> importTools.confirmTimelineImport(proposed.proposalId(), null));
        assertEquals("AIRCRAFT_REMOVED", r.status());
        assertEquals("That aircraft was removed. Start a new import.", r.message());
    }

    @Autowired com.example.AviaryService.repositories.ImportProposalRepository proposalRepository;
}
