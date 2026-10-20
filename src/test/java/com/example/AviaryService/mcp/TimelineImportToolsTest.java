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

import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.services.imports.TimelineImportService.ImportResult;
import com.example.AviaryService.util.TimelineImportRules.InputItem;
import com.fasterxml.jackson.databind.ObjectMapper;

// The MCP tool layer: scopes are enforced, the token owner is the only user a
// tool can touch, and outputs never carry the User entity.
@SpringBootTest
@ActiveProfiles("test")
class TimelineImportToolsTest {

    @Autowired TimelineImportTools tools;
    @Autowired UserRepository userRepository;
    @Autowired ServiceTimelineRepository timelineRepository;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        alice = saveUser("tools-alice-");
        bob = saveUser("tools-bob-");
        ServiceTimeline t = new ServiceTimeline();
        t.setUser(bob);
        t.setItem("Bob's annual");
        t.setTimelineOrder(0);
        timelineRepository.save(t);
    }

    private User saveUser(String prefix) {
        User u = new User();
        u.setUsername(prefix + System.nanoTime());
        u.setPassword("secret-hash");
        u.setTimeInServiceHours(500.0);
        return userRepository.save(u);
    }

    private static List<InputItem> oneItem() {
        return List.of(new InputItem("Oil", null, null, null, "450", null, null, "50", null, null, null, "Oil 450 / 50", null));
    }

    @Test
    void importFormatHasLimitAndExample() {
        TimelineImportTools.ImportFormat f = McpTestSupport.as(alice.getUsername(), () -> tools.getImportFormat());
        assertEquals(200, f.maxItems());
        assertFalse(f.example().isBlank());
    }

    @Test
    void listOnlyShowsOwnRowsAndNoUser() throws Exception {
        List<TimelineImportTools.TimelineRow> mine = McpTestSupport.as(alice.getUsername(), () -> tools.listServiceTimeline());
        assertEquals(0, mine.size());
        List<TimelineImportTools.TimelineRow> bobs = McpTestSupport.as(bob.getUsername(), () -> tools.listServiceTimeline());
        assertEquals(1, bobs.size());
        String json = new ObjectMapper().writeValueAsString(bobs);
        assertFalse(json.contains("secret-hash"));
        assertFalse(json.contains("\"user\""));
    }

    @Test
    void importToolsNeedImportScope() {
        assertThrows(AccessDeniedException.class, () -> McpTestSupport.as(alice.getUsername(), List.of("aviary.read"),
            () -> tools.proposeTimelineImport(oneItem(), null)));
        assertThrows(AccessDeniedException.class, () -> McpTestSupport.as(alice.getUsername(), List.of("aviary.read"),
            () -> tools.confirmTimelineImport(1L, null)));
        assertThrows(AccessDeniedException.class, () -> McpTestSupport.as(alice.getUsername(), List.of("aviary.read"),
            () -> tools.continuePoorFit(1L)));
    }

    @Test
    void proposeThenConfirmAsOwner() {
        ImportResult proposed = McpTestSupport.as(alice.getUsername(), () -> tools.proposeTimelineImport(oneItem(), null));
        assertEquals("PROPOSED", proposed.status());

        // Bob can't confirm Alice's proposal.
        assertEquals("NOT_FOUND", McpTestSupport.as(bob.getUsername(),
            () -> tools.confirmTimelineImport(proposed.proposalId(), null)).status());

        ImportResult imported = McpTestSupport.as(alice.getUsername(),
            () -> tools.confirmTimelineImport(proposed.proposalId(), null));
        assertEquals("IMPORTED", imported.status());
        assertEquals(1, imported.addedCount());
        assertEquals(1, McpTestSupport.as(alice.getUsername(), () -> tools.listServiceTimeline()).size());
    }
}
