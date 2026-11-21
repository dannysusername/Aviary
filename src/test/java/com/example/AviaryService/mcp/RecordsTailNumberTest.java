package com.example.AviaryService.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.StatusSheetItem;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.ImportProposalRepository;
import com.example.AviaryService.repositories.StatusSheetItemRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.services.imports.StatusSheetImportService.ImportResult;
import com.example.AviaryService.support.TestFleet;
import com.example.AviaryService.util.StatusSheetImportRules.InputItem;

// Spec 012 US2: a Status Sheet for N77XY never lands on N12SAS.
// See specs/012-mcp-add-aircraft/contracts/mcp-tools.md. Fresh users per test.
@SpringBootTest
@ActiveProfiles("test")
class RecordsTailNumberTest {

    @Autowired StatusSheetImportTools importTools;
    @Autowired UserRepository userRepository;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired StatusSheetItemRepository statusSheetItemRepository;
    @Autowired ImportProposalRepository proposalRepository;

    private User newUser(String prefix) {
        User u = new User();
        u.setUsername(prefix + System.nanoTime());
        u.setPassword("x");
        return userRepository.save(u);
    }

    private static List<InputItem> items(String name) {
        return List.of(new InputItem(name, null, null, null, "450", null, null, "50", null, null, null, name + " row", null));
    }

    private void existing(User u, Aircraft a, String name) {
        StatusSheetItem t = new StatusSheetItem();
        t.setUser(u);
        t.setAircraft(a);
        t.setItem(name);
        t.setStatusSheetOrder(0);
        statusSheetItemRepository.save(t);
    }

    private ImportResult propose(User u, String aircraft, String recordsTail) {
        return McpTestSupport.as(u.getUsername(),
            () -> importTools.proposeStatusSheetImport(items("Annual"), null, aircraft, recordsTail));
    }

    @Test
    void recordsForAPlaneNotOnTheAccountAreStoppedEvenWithOneAircraft() {
        User owner = newUser("solo-");
        Aircraft n12 = TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N12SAS", "", 0, 0);
        existing(owner, n12, "Oil change");

        ImportResult r = propose(owner, null, "N77XY");

        assertEquals("ADD_AIRCRAFT_FIRST", r.status());
        assertNull(r.proposalId());
        assertTrue(r.message().contains("N77XY") && r.message().contains("add_aircraft"), r.message());
        assertTrue(proposalRepository.findByUserAndStatusIn(owner, List.of("OPEN", "NEEDS_FIT_CONFIRMATION")).isEmpty());
        assertEquals(List.of("Oil change"), statusSheetItemRepository.findByAircraftOrderByStatusSheetOrderAsc(n12)
            .stream().map(StatusSheetItem::getItem).toList());
    }

    @Test
    void namedAircraftThatDisagreesWithTheRecordsIsStopped() {
        User owner = newUser("pair-");
        TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N12SAS", "", 0, 0);
        TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N77XY", "", 0, 0);

        ImportResult r = propose(owner, "N12SAS", "N77XY");

        assertEquals("AIRCRAFT_MISMATCH", r.status());
        assertNull(r.proposalId());
        assertTrue(r.message().contains("N12SAS") && r.message().contains("N77XY"), r.message());
    }

    @Test
    void recordsTailPicksThePlaneWhenNoAircraftIsNamed() {
        User owner = newUser("pick-");
        TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N12SAS", "", 0, 0);
        TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N77XY", "", 0, 0);

        ImportResult r = propose(owner, null, "n77 xy");

        assertEquals("PROPOSED", r.status());
        assertTrue(r.summary().startsWith("Importing into N77XY"), r.summary());
    }

    @Test
    void namedAircraftAndRecordsAgree() {
        User owner = newUser("agree-");
        TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N12SAS", "", 0, 0);
        TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N77XY", "", 0, 0);

        assertEquals("PROPOSED", propose(owner, "N77XY", "N77XY").status());
    }

    @Test
    void withoutRecordsTailTodaysRulesApply() {
        User solo = newUser("today-solo-");
        TestFleet.addAircraft(aircraftRepository, userRepository, solo, "N12SAS", "", 0, 0);
        assertEquals("PROPOSED", propose(solo, null, null).status());

        User pair = newUser("today-pair-");
        TestFleet.addAircraft(aircraftRepository, userRepository, pair, "N12SAS", "", 0, 0);
        TestFleet.addAircraft(aircraftRepository, userRepository, pair, "N77XY", "", 0, 0);
        assertEquals("CHOOSE_AIRCRAFT", propose(pair, null, "  ").status());
    }

    @Test
    void anotherAccountsPlaneNeverMatches() {
        User a = newUser("acct-a-");
        TestFleet.addAircraft(aircraftRepository, userRepository, a, "N77XY", "", 0, 0);
        User b = newUser("acct-b-");
        TestFleet.addAircraft(aircraftRepository, userRepository, b, "N12SAS", "", 0, 0);

        assertEquals("ADD_AIRCRAFT_FIRST", propose(b, null, "N77XY").status());
    }
}
