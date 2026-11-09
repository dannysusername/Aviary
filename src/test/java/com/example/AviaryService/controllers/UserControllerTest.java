package com.example.AviaryService.controllers;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.DescriptionOption;
import com.example.AviaryService.entity.FlightLog;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.DescriptionOptionRepository;
import com.example.AviaryService.repositories.FlightLogRepository;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ServiceTimelineRepository serviceTimelineRepository;

    @Autowired
    private DescriptionOptionRepository descriptionOptionRepository;

    @Autowired
    private FlightLogRepository flightLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AircraftRepository aircraftRepository;

    @Autowired
    private JdbcTemplate jdbc;

    // The user's (only) aircraft, freshly loaded.
    private Aircraft plane(String username) {
        return aircraftRepository.findFirstByUserOrderByIdAsc(userRepository.findByUsername(username)).orElseThrow();
    }

    @BeforeEach
    void setUp() {
        // Clear existing data to avoid duplicates and foreign key violations
        descriptionOptionRepository.deleteAll();
        serviceTimelineRepository.deleteAll();
        flightLogRepository.deleteAll();
        // users.last_viewed_aircraft_id points at aircraft; clear it so the
        // users (and their cascaded aircraft) can be deleted.
        jdbc.update("UPDATE users SET last_viewed_aircraft_id = NULL");
        jdbc.update("DELETE FROM flight_suggestions");
        jdbc.update("DELETE FROM subscriptions");
        jdbc.update("DELETE FROM import_proposals");
        aircraftRepository.deleteAll();
        userRepository.deleteAll();

        // Create 50 users with 2 timelines each
        for (int i = 1; i <= 50; i++) {
            User user = new User();
            user.setUsername("user" + i);
            user.setPassword(passwordEncoder.encode("password" + i));
            userRepository.save(user);
            Aircraft aircraft = aircraftRepository.save(new Aircraft(user));
            user.setLastViewedAircraft(aircraft);
            userRepository.save(user);

            for (int j = 1; j <= 2; j++) {
                ServiceTimeline timeline = new ServiceTimeline();
                timeline.setUser(user);
                timeline.setAircraft(aircraft);
                timeline.setItem("Timeline " + j + " for " + user.getUsername());
                timeline.setIsTitle(false);
                timeline.setTimelineOrder(j);
                timeline.setDescription("Test Description");
                timeline.setTimeLeft("7 days");
                serviceTimelineRepository.save(timeline);
            }
        }
    }

    @Test
    @WithMockUser(username = "user1", roles = {"USER"})
    void testAddTimelineAsTitle() throws Exception {
        Map<String, String> data = new HashMap<>();
        data.put("item", "Test Item");
        data.put("isTitle", "true");

        mockMvc.perform(post("/dashboard")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"item\":\"Test Item\",\"isTitle\":\"true\"}")
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/dashboard?aircraft=" + plane("user1").getId()));

        // Verify data in PostgreSQL
        User user = userRepository.findByUsername("user1");
        List<ServiceTimeline> timelines = serviceTimelineRepository.findByUserOrderByTimelineOrderAsc(user);
        assertTrue(timelines.stream().anyMatch(t -> t.getItem().equals("Test Item") && Boolean.TRUE.equals(t.getIsTitle())));
    }

    @Test
    @WithMockUser(username = "user2", roles = {"USER"})
    void testAddTimelineWithDescription() throws Exception {
        Map<String, String> data = new HashMap<>();
        data.put("item", "Item");
        data.put("isTitle", "false");
        data.put("description", "Custom Desc");
        data.put("cycle", "Weekly");
        data.put("lastDone", "2025-10-01");
        data.put("dueDate", "2025-10-08");
        data.put("timeLeft", "7 days");

        mockMvc.perform(post("/dashboard")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"item\":\"Item\",\"isTitle\":\"false\",\"description\":\"Custom Desc\",\"cycle\":\"Weekly\",\"lastDone\":\"2025-10-01\",\"dueDate\":\"2025-10-08\",\"timeLeft\":\"7 days\"}")
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/dashboard?aircraft=" + plane("user2").getId()));

        // Verify data in PostgreSQL
        User user = userRepository.findByUsername("user2");
        List<ServiceTimeline> timelines = serviceTimelineRepository.findByUserOrderByTimelineOrderAsc(user);
        assertTrue(timelines.stream().anyMatch(t -> t.getItem().equals("Item") && t.getDescription().equals("Custom Desc")));
    }

    @Test
    @WithMockUser(username = "user1", roles = {"USER"})
    void testUpdateHoursStampsManualSource() throws Exception {
        mockMvc.perform(post("/updateHours")
                .param("newTimeInService", "123.4")
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());

        Aircraft user = plane("user1");
        assertTrue(user.getTimeInServiceUpdatedAt() != null, "timeInServiceUpdatedAt should be set after a manual edit");
        assertTrue("manual".equals(user.getTimeInServiceUpdatedSource()), "timeInService source should be 'manual'");
    }

    @Test
    @WithMockUser(username = "user2", roles = {"USER"})
    void testAddFlightLogStampsFlightlogSource() throws Exception {
        mockMvc.perform(post("/addflightlog")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAirport\":\"KAAA\",\"toAirport\":\"KBBB\",\"blockTimeOut\":10.0,\"blockTimeIn\":11.0,\"timeInServiceOut\":9.0,\"timeInServiceIn\":10.0}")
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());

        Aircraft user = plane("user2");
        assertTrue("flightlog".equals(user.getTimeInServiceUpdatedSource()), "timeInService source should be 'flightlog'");
        assertTrue(user.getTimeInServiceUpdatedAt() != null, "timeInServiceUpdatedAt should be set after a flight log");
    }

    // ── Regression tests for the meter-snapshot rewrite ───────────────────────
    // These pin down the three bugs from the original /addflightlog logic:
    //   1. Partial entry (e.g. only timeInServiceOut) used to wipe BOTH meters to 0.
    //   2. Manual edits used to be silently overwritten by log activity.
    //   3. Deleting a log used to set hours to 0 instead of reverting.

    @Test
    @WithMockUser(username = "user3", roles = {"USER"})
    void testAddFlightLogRejectsPartialPairAndPreservesHours() throws Exception {
        // Set up a user with manually-entered hours.
        Aircraft u = plane("user3");
        u.setBlockTimeHours(100.0);
        u.setBlockTimeManualBaseline(100.0);
        u.setTimeInServiceHours(100.0);
        u.setTimeInServiceManualBaseline(100.0);
        aircraftRepository.save(u);

        // The original bug: posting a log with only timeInServiceOut filled in.
        mockMvc.perform(post("/addflightlog")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAirport\":\"KAAA\",\"toAirport\":\"KBBB\",\"timeInServiceOut\":500.0}")
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest());

        // Critical: the meters must NOT have been touched.
        Aircraft after = plane("user3");
        assertEquals(100.0, after.getBlockTimeHours(), 0.0001, "BlockTime hours must not be wiped by a rejected partial entry");
        assertEquals(100.0, after.getTimeInServiceHours(), 0.0001, "TimeInService hours must not be wiped by a rejected partial entry");
        assertTrue(flightLogRepository.findByAircraft(after).isEmpty(), "No partial log should have been saved");
    }

    @Test
    @WithMockUser(username = "user4", roles = {"USER"})
    void testManualBaselineActsAsFloorAgainstLowerLogReadings() throws Exception {
        // User manually claims 200 hours on the airframe.
        Aircraft u = plane("user4");
        u.setBlockTimeHours(200.0);
        u.setBlockTimeManualBaseline(200.0);
        u.setTimeInServiceHours(200.0);
        u.setTimeInServiceManualBaseline(200.0);
        aircraftRepository.save(u);

        // Add a complete log whose blockTimeIn=50 is well BELOW the manual floor.
        mockMvc.perform(post("/addflightlog")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"blockTimeOut\":49.5,\"blockTimeIn\":50.0,\"timeInServiceOut\":49.5,\"timeInServiceIn\":50.0}")
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());

        Aircraft after = plane("user4");
        assertEquals(200.0, after.getBlockTimeHours(), 0.0001, "Manual baseline must not be overwritten by a lower log reading");
        assertEquals(200.0, after.getTimeInServiceHours(), 0.0001, "Manual baseline must not be overwritten by a lower log reading");
    }

    @Test
    @WithMockUser(username = "user5", roles = {"USER"})
    void testDeleteFlightLogRevertsToBaseline() throws Exception {
        // Start at a manual baseline of 100.
        Aircraft u = plane("user5");
        u.setBlockTimeHours(100.0);
        u.setBlockTimeManualBaseline(100.0);
        u.setTimeInServiceHours(100.0);
        u.setTimeInServiceManualBaseline(100.0);
        aircraftRepository.save(u);

        // Add a log that raises blockTime to 150 and timeInService to 145.
        mockMvc.perform(post("/addflightlog")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"blockTimeOut\":100.0,\"blockTimeIn\":150.0,\"timeInServiceOut\":100.0,\"timeInServiceIn\":145.0}")
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());

        Aircraft afterAdd = plane("user5");
        assertEquals(150.0, afterAdd.getBlockTimeHours(), 0.0001, "BlockTime should be raised by the log");
        assertEquals(145.0, afterAdd.getTimeInServiceHours(), 0.0001, "TimeInService should be raised by the log");

        // Delete that log — meters must revert to the baseline, not crash to 0.
        List<FlightLog> logs = flightLogRepository.findByAircraft(afterAdd);
        assertEquals(1, logs.size());
        Long logId = logs.get(0).getId();

        mockMvc.perform(delete("/deleteflightlog/" + logId)
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());

        Aircraft afterDelete = plane("user5");
        assertEquals(100.0, afterDelete.getBlockTimeHours(), 0.0001, "Delete must revert BlockTime to the manual baseline");
        assertEquals(100.0, afterDelete.getTimeInServiceHours(), 0.0001, "Delete must revert TimeInService to the manual baseline");
    }
}