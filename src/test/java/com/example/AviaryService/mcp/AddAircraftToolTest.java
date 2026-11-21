package com.example.AviaryService.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.StatusSheetItem;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.mcp.AircraftTools.AddAircraftResult;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.StatusSheetItemRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.services.imports.StatusSheetImportService.ImportResult;
import com.example.AviaryService.support.TestFleet;
import com.example.AviaryService.util.StatusSheetImportRules.InputItem;

// Spec 012 US3/US4: the assistant adds aircraft and imports a Status Sheet
// into each. See specs/012-mcp-add-aircraft/contracts/mcp-tools.md.
// Not @Transactional: confirm runs its own transaction. Fresh users per test.
@SpringBootTest
@ActiveProfiles("test")
class AddAircraftToolTest {

    @Autowired AircraftTools aircraftTools;
    @Autowired StatusSheetImportTools importTools;
    @Autowired UserRepository userRepository;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired StatusSheetItemRepository statusSheetItemRepository;

    private User owner;
    private Aircraft n12;

    @BeforeEach
    void setUp() {
        owner = newUser("add-tool-");
        n12 = TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N12SAS", "", 0, 100);
        owner = userRepository.findById(owner.getId()).orElseThrow();
        owner.setLastViewedAircraft(n12);
        userRepository.save(owner);
    }

    private User newUser(String prefix) {
        User u = new User();
        u.setUsername(prefix + System.nanoTime());
        u.setPassword("x");
        return userRepository.save(u);
    }

    private AddAircraftResult add(User u, String tail, String makeModel) {
        return McpTestSupport.as(u.getUsername(), () -> aircraftTools.addAircraft(tail, makeModel, null, null));
    }

    private static List<InputItem> items(String name) {
        return List.of(new InputItem(name, null, null, null, "450", null, null, "50", null, null, null, name + " row", null));
    }

    private List<String> rowsOn(Aircraft a) {
        return statusSheetItemRepository.findByAircraftOrderByStatusSheetOrderAsc(a).stream()
            .map(StatusSheetItem::getItem).toList();
    }

    private Aircraft byId(Long id) {
        return aircraftRepository.findById(id).orElseThrow();
    }

    @Test
    void addsAnEmptyAircraftThatListAircraftShows() {
        AddAircraftResult r = add(owner, "N731KR", "Piper PA-28");

        assertEquals("ADDED", r.status());
        assertEquals("Added N731KR.", r.message());
        assertEquals("N731KR", r.aircraft().tailNumber());
        assertEquals("Piper PA-28", r.aircraft().makeModel());
        assertEquals(0.0, r.aircraft().timeInServiceHours());
        assertEquals(0, r.aircraft().statusSheetItems());
        List<AircraftTools.AircraftSummary> fleet = McpTestSupport.as(owner.getUsername(), () -> aircraftTools.listAircraft());
        assertEquals(List.of("N12SAS", "N731KR"), fleet.stream().map(AircraftTools.AircraftSummary::tailNumber).toList());
    }

    @Test
    void addingDoesNotMoveTheDashboard() {
        add(owner, "N731KR", null);
        assertEquals(n12.getId(), userRepository.findById(owner.getId()).orElseThrow().getLastViewedAircraft().getId());
    }

    @Test
    void duplicateReportsTheExistingAircraft() {
        Long first = add(owner, "N731KR", null).aircraft().aircraftId();

        AddAircraftResult again = add(owner, "n731 kr", null);

        assertEquals("ALREADY_EXISTS", again.status());
        assertEquals(first, again.aircraft().aircraftId());
        assertEquals("N731KR is already on this account (aircraftId " + first + "). Import into it instead.", again.message());
        assertEquals(2, aircraftRepository.countByUser(owner));
    }

    @Test
    void invalidTailIsRefusedWithTheDashboardsMessage() {
        AddAircraftResult blank = add(owner, "  ", null);
        assertEquals("INVALID", blank.status());
        assertEquals("Enter a tail number.", blank.message());
        assertNull(blank.aircraft());
        assertEquals(1, aircraftRepository.countByUser(owner));
    }

    @Test
    void limitIsReported() {
        for (int i = 2; i <= Aircraft.MAX_PER_USER; i++) {
            assertEquals("ADDED", add(owner, "N" + i + "XX", null).status());
        }
        AddAircraftResult r = add(owner, "N999ZZ", null);
        assertEquals("LIMIT_REACHED", r.status());
        assertEquals("You can track up to 25 aircraft.", r.message());
        assertEquals(Aircraft.MAX_PER_USER, aircraftRepository.countByUser(owner));
    }

    @Test
    void readOnlyConnectionIsRefused() {
        assertThrows(AccessDeniedException.class, () -> McpTestSupport.as(owner.getUsername(), List.of("aviary.read"),
            () -> aircraftTools.addAircraft("N731KR", null, null, null)));
        assertEquals(1, aircraftRepository.countByUser(owner));
    }

    @Test
    void anotherAccountWithTheSameTailGetsItsOwnAircraft() {
        User other = newUser("add-tool-other-");
        TestFleet.addAircraft(aircraftRepository, userRepository, other, "N1OT", "", 0, 0);

        AddAircraftResult r = add(other, "N12SAS", null);

        assertEquals("ADDED", r.status());
        assertEquals(1, aircraftRepository.countByUser(owner));
        assertEquals(2, aircraftRepository.countByUser(other));
    }

    // Spec 012 US2 scenario 1, end to end: N12SAS's account gets a sheet for N77XY.
    @Test
    void sheetForNewPlaneOnOneAircraftAccount() {
        statusSheetItemRepository.save(row(n12, "N12SAS Oil"));

        ImportResult stopped = McpTestSupport.as(owner.getUsername(),
            () -> importTools.proposeStatusSheetImport(items("N77XY Annual"), null, null, "N77XY"));
        assertEquals("ADD_AIRCRAFT_FIRST", stopped.status());

        Long n77 = add(owner, "N77XY", null).aircraft().aircraftId();
        ImportResult proposed = McpTestSupport.as(owner.getUsername(),
            () -> importTools.proposeStatusSheetImport(items("N77XY Annual"), null, null, "N77XY"));
        assertEquals("PROPOSED", proposed.status());
        assertTrue(proposed.summary().startsWith("Importing into N77XY"), proposed.summary());
        assertEquals("IMPORTED", McpTestSupport.as(owner.getUsername(),
            () -> importTools.confirmStatusSheetImport(proposed.proposalId(), null)).status());

        assertEquals(List.of("N77XY Annual"), rowsOn(byId(n77)));
        assertEquals(List.of("N12SAS Oil"), rowsOn(n12));
    }

    // Spec 012 US4: three new planes in one session, each gets only its rows.
    @Test
    void threeNewAircraftEachGetOnlyTheirRows() {
        statusSheetItemRepository.save(row(n12, "N12SAS Oil"));
        List<String> tails = List.of("N1AA", "N2BB", "N3CC");
        Long[] ids = new Long[3];
        Long[] proposals = new Long[3];
        for (int i = 0; i < 3; i++) {
            String tail = tails.get(i);
            ids[i] = add(owner, tail, null).aircraft().aircraftId();
            ImportResult p = McpTestSupport.as(owner.getUsername(),
                () -> importTools.proposeStatusSheetImport(items(tail + " Annual"), null, tail, tail));
            assertEquals("PROPOSED", p.status());
            proposals[i] = p.proposalId();
        }
        // N1AA's proposal stayed open while N2BB and N3CC were proposed.
        for (Long proposalId : proposals) {
            assertEquals("IMPORTED", McpTestSupport.as(owner.getUsername(),
                () -> importTools.confirmStatusSheetImport(proposalId, null)).status());
        }
        for (int i = 0; i < 3; i++) {
            assertEquals(List.of(tails.get(i) + " Annual"), rowsOn(byId(ids[i])));
        }
        assertEquals(List.of("N12SAS Oil"), rowsOn(n12));
    }

    private StatusSheetItem row(Aircraft a, String name) {
        StatusSheetItem t = new StatusSheetItem();
        t.setUser(owner);
        t.setAircraft(a);
        t.setItem(name);
        t.setStatusSheetOrder(0);
        return t;
    }
}
