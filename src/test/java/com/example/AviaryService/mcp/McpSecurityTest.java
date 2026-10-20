package com.example.AviaryService.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.opaqueToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.FlightLog;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.FlightLogRepository;
import com.example.AviaryService.repositories.UserRepository;

// /mcp is closed to anyone without a valid Aviary-issued token, and a tool
// only ever sees the token owner's data (constitution Principle I).
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class McpSecurityTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired FlightLogRepository flightLogRepository;
    @Autowired FlightTools flightTools;
    @Autowired McpCurrentUser currentUser;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        alice = saveUser("alice-");
        bob = saveUser("bob-");
        FlightLog log = new FlightLog();
        log.setUser(bob);
        log.setFromAirport("KSFO");
        log.setToAirport("KLAX");
        flightLogRepository.save(log);
    }

    private User saveUser(String prefix) {
        User u = new User();
        u.setUsername(prefix + System.nanoTime());
        u.setPassword("x");
        return userRepository.save(u);
    }

    @Test
    void noTokenGets401PointingAtMetadata() throws Exception {
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate",
                "Bearer resource_metadata=\"http://localhost:8080/.well-known/oauth-protected-resource\""));
    }

    @Test
    void unknownTokenGets401() throws Exception {
        mvc.perform(post("/mcp").header("Authorization", "Bearer not-a-real-token")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithoutReadScopeGets403() throws Exception {
        mvc.perform(post("/mcp")
                .with(opaqueToken().attributes(a -> a.put("sub", alice.getUsername()))
                    .authorities(new SimpleGrantedAuthority("SCOPE_something.else")))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void flightToolsOnlySeeTheTokenOwnersFlights() {
        assertEquals(0, McpTestSupport.as(alice.getUsername(), () -> flightTools.listFlights()).size());
        assertEquals(1, McpTestSupport.as(bob.getUsername(), () -> flightTools.listFlights()).size());
    }

    @Test
    void noSignedInUserMeansNoAccess() {
        assertThrows(AccessDeniedException.class, () -> flightTools.listFlights());
    }

    @Test
    void missingScopeIsRefusedInsideTools() {
        assertThrows(AccessDeniedException.class,
            () -> McpTestSupport.as(alice.getUsername(), List.of("aviary.read"),
                () -> { currentUser.requireScope("aviary.timeline.import"); return null; }));
        assertTrue(McpTestSupport.as(alice.getUsername(), List.of("aviary.read"),
            () -> currentUser.require().getUsername().equals(alice.getUsername())));
    }
}
