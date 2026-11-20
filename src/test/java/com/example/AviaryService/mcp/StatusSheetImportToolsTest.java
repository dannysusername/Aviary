package com.example.AviaryService.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import com.example.AviaryService.entity.StatusSheetItem;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.StatusSheetItemRepository;
import com.example.AviaryService.support.TestFleet;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.services.imports.StatusSheetImportService.ImportResult;
import com.example.AviaryService.util.StatusSheetImportRules.InputItem;
import com.fasterxml.jackson.databind.ObjectMapper;

// The MCP tool layer: scopes are enforced, the token owner is the only user a
// tool can touch, and outputs never carry the User entity.
@SpringBootTest
@ActiveProfiles("test")
class StatusSheetImportToolsTest {

    @Autowired StatusSheetImportTools tools;
    @Autowired UserRepository userRepository;
    @Autowired StatusSheetItemRepository statusSheetItemRepository;
    @Autowired AircraftRepository aircraftRepository;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        alice = saveUser("tools-alice-");
        bob = saveUser("tools-bob-");
        StatusSheetItem t = new StatusSheetItem();
        t.setUser(bob);
        t.setAircraft(aircraftRepository.findFirstByUserOrderByIdAsc(bob).orElseThrow());
        t.setItem("Bob's annual");
        t.setStatusSheetOrder(0);
        statusSheetItemRepository.save(t);
    }

    private User saveUser(String prefix) {
        User u = new User();
        u.setUsername(prefix + System.nanoTime());
        u.setPassword("secret-hash");
        userRepository.save(u);
        TestFleet.addAircraft(aircraftRepository, userRepository, u, "", "", 0, 500.0);
        return u;
    }

    private static List<InputItem> oneItem() {
        return List.of(new InputItem("Oil", null, null, null, "450", null, null, "50", null, null, null, "Oil 450 / 50", null));
    }

    @Test
    void importFormatHasLimitAndExample() {
        StatusSheetImportTools.ImportFormat f = McpTestSupport.as(alice.getUsername(), () -> tools.getImportFormat());
        assertEquals(200, f.maxItems());
        assertFalse(f.example().isBlank());
    }

    @Test
    void listOnlyShowsOwnRowsAndNoUser() throws Exception {
        List<StatusSheetImportTools.StatusSheetRow> mine = McpTestSupport.as(alice.getUsername(), () -> tools.listStatusSheetItem(null));
        assertEquals(0, mine.size());
        List<StatusSheetImportTools.StatusSheetRow> bobs = McpTestSupport.as(bob.getUsername(), () -> tools.listStatusSheetItem(null));
        assertEquals(1, bobs.size());
        String json = new ObjectMapper().writeValueAsString(bobs);
        assertFalse(json.contains("secret-hash"));
        assertFalse(json.contains("\"user\""));
    }

    @Test
    void importToolsNeedImportScope() {
        assertThrows(AccessDeniedException.class, () -> McpTestSupport.as(alice.getUsername(), List.of("aviary.read"),
            () -> tools.proposeStatusSheetImport(oneItem(), null, null)));
        assertThrows(AccessDeniedException.class, () -> McpTestSupport.as(alice.getUsername(), List.of("aviary.read"),
            () -> tools.confirmStatusSheetImport(1L, null)));
        assertThrows(AccessDeniedException.class, () -> McpTestSupport.as(alice.getUsername(), List.of("aviary.read"),
            () -> tools.continuePoorFit(1L)));
    }

    @Test
    void proposeThenConfirmAsOwner() {
        ImportResult proposed = McpTestSupport.as(alice.getUsername(), () -> tools.proposeStatusSheetImport(oneItem(), null, null));
        assertEquals("PROPOSED", proposed.status());

        // Bob can't confirm Alice's proposal.
        assertEquals("NOT_FOUND", McpTestSupport.as(bob.getUsername(),
            () -> tools.confirmStatusSheetImport(proposed.proposalId(), null)).status());

        ImportResult imported = McpTestSupport.as(alice.getUsername(),
            () -> tools.confirmStatusSheetImport(proposed.proposalId(), null));
        assertEquals("IMPORTED", imported.status());
        assertEquals(1, imported.addedCount());
        assertEquals(1, McpTestSupport.as(alice.getUsername(), () -> tools.listStatusSheetItem(null)).size());
    }
}
