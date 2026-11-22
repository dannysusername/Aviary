package com.example.AviaryService.controllers;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.DescriptionOption;
import com.example.AviaryService.entity.FlightLog;
import com.example.AviaryService.entity.StatusSheetItem;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.DescriptionOptionRepository;
import com.example.AviaryService.repositories.FlightLogRepository;
import com.example.AviaryService.repositories.StatusSheetItemRepository;
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
    private StatusSheetItemRepository statusSheetItemRepository;

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
        statusSheetItemRepository.deleteAll();
        flightLogRepository.deleteAll();
        // users.last_viewed_aircraft_id points at aircraft; clear it so the
        // users (and their cascaded aircraft) can be deleted.
        jdbc.update("UPDATE users SET last_viewed_aircraft_id = NULL");
        jdbc.update("DELETE FROM flight_suggestions");
        jdbc.update("DELETE FROM subscriptions");
        jdbc.update("DELETE FROM import_proposals");
        // Alert rows left by non-transactional alert tests (spec 014 simulations).
        jdbc.update("DELETE FROM alert_send_log");
        jdbc.update("DELETE FROM alert_recipients");
        jdbc.update("DELETE FROM alert_preferences");
        jdbc.update("DELETE FROM timeline_import_records");
        aircraftRepository.deleteAll();
        userRepository.deleteAll();

        // Create 50 users with 2 Status Sheet items each
        for (int i = 1; i <= 50; i++) {
            User user = new User();
            user.setUsername("user" + i);
            user.setPassword(passwordEncoder.encode("password" + i));
            userRepository.save(user);
            Aircraft aircraft = aircraftRepository.save(new Aircraft(user));
            user.setLastViewedAircraft(aircraft);
            userRepository.save(user);

            for (int j = 1; j <= 2; j++) {
                StatusSheetItem statusSheetItem = new StatusSheetItem();
                statusSheetItem.setUser(user);
                statusSheetItem.setAircraft(aircraft);
                statusSheetItem.setItem("Status Sheet item " + j + " for " + user.getUsername());
                statusSheetItem.setIsTitle(false);
                statusSheetItem.setStatusSheetOrder(j);
                statusSheetItem.setDescription("Test Description");
                statusSheetItem.setTimeLeft("7 days");
                statusSheetItemRepository.save(statusSheetItem);
            }
        }
    }

    @Test
    @WithMockUser(username = "user1", roles = {"USER"})
    void testAddStatusSheetAsTitle() throws Exception {
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
        List<StatusSheetItem> statusSheetItems = statusSheetItemRepository.findByUserOrderByStatusSheetOrderAsc(user);
        assertTrue(statusSheetItems.stream().anyMatch(t -> t.getItem().equals("Test Item") && Boolean.TRUE.equals(t.getIsTitle())));
    }

    @Test
    @WithMockUser(username = "user2", roles = {"USER"})
    void testAddStatusSheetWithDescription() throws Exception {
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
        List<StatusSheetItem> statusSheetItems = statusSheetItemRepository.findByUserOrderByStatusSheetOrderAsc(user);
        assertTrue(statusSheetItems.stream().anyMatch(t -> t.getItem().equals("Item") && t.getDescription().equals("Custom Desc")));
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
                .content("{\"fromAirport\":\"KAAA\",\"toAirport\":\"KBBB\",\"hobbsTimeOut\":10.0,\"hobbsTimeIn\":11.0,\"timeInServiceOut\":9.0,\"timeInServiceIn\":10.0}")
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
        u.setHobbsTimeHours(100.0);
        u.setHobbsTimeManualBaseline(100.0);
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
        assertEquals(100.0, after.getHobbsTimeHours(), 0.0001, "HobbsTime hours must not be wiped by a rejected partial entry");
        assertEquals(100.0, after.getTimeInServiceHours(), 0.0001, "TimeInService hours must not be wiped by a rejected partial entry");
        assertTrue(flightLogRepository.findByAircraft(after).isEmpty(), "No partial log should have been saved");
    }

    @Test
    @WithMockUser(username = "user4", roles = {"USER"})
    void testManualBaselineActsAsFloorAgainstLowerLogReadings() throws Exception {
        // User manually claims 200 hours on the airframe.
        Aircraft u = plane("user4");
        u.setHobbsTimeHours(200.0);
        u.setHobbsTimeManualBaseline(200.0);
        u.setTimeInServiceHours(200.0);
        u.setTimeInServiceManualBaseline(200.0);
        aircraftRepository.save(u);

        // Add a complete log whose hobbsTimeIn=50 is well BELOW the manual floor.
        mockMvc.perform(post("/addflightlog")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"hobbsTimeOut\":49.5,\"hobbsTimeIn\":50.0,\"timeInServiceOut\":49.5,\"timeInServiceIn\":50.0}")
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());

        Aircraft after = plane("user4");
        assertEquals(200.0, after.getHobbsTimeHours(), 0.0001, "Manual baseline must not be overwritten by a lower log reading");
        assertEquals(200.0, after.getTimeInServiceHours(), 0.0001, "Manual baseline must not be overwritten by a lower log reading");
    }

    @Test
    @WithMockUser(username = "user5", roles = {"USER"})
    void testDeleteFlightLogRevertsToBaseline() throws Exception {
        // Start at a manual baseline of 100.
        Aircraft u = plane("user5");
        u.setHobbsTimeHours(100.0);
        u.setHobbsTimeManualBaseline(100.0);
        u.setTimeInServiceHours(100.0);
        u.setTimeInServiceManualBaseline(100.0);
        aircraftRepository.save(u);

        // Add a log that raises hobbsTime to 150 and timeInService to 145.
        mockMvc.perform(post("/addflightlog")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"hobbsTimeOut\":100.0,\"hobbsTimeIn\":150.0,\"timeInServiceOut\":100.0,\"timeInServiceIn\":145.0}")
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());

        Aircraft afterAdd = plane("user5");
        assertEquals(150.0, afterAdd.getHobbsTimeHours(), 0.0001, "HobbsTime should be raised by the log");
        assertEquals(145.0, afterAdd.getTimeInServiceHours(), 0.0001, "TimeInService should be raised by the log");

        // Delete that log — meters must revert to the baseline, not crash to 0.
        List<FlightLog> logs = flightLogRepository.findByAircraft(afterAdd);
        assertEquals(1, logs.size());
        Long logId = logs.get(0).getId();

        mockMvc.perform(delete("/deleteflightlog/" + logId)
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());

        Aircraft afterDelete = plane("user5");
        assertEquals(100.0, afterDelete.getHobbsTimeHours(), 0.0001, "Delete must revert HobbsTime to the manual baseline");
        assertEquals(100.0, afterDelete.getTimeInServiceHours(), 0.0001, "Delete must revert TimeInService to the manual baseline");
    }

    // ── Hobbs Time rename (spec 005): old names from a tab still running the
    // pre-rename dashboard.js must keep saving, not be silently dropped.

    @Test
    @WithMockUser(username = "user10", roles = {"USER"})
    void legacyBlockTimeJsonNamesStillSaveHobbsValues() throws Exception {
        mockMvc.perform(post("/addflightlog")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAirport\":\"KAAA\",\"toAirport\":\"KBBB\",\"blockTimeOut\":10.0,\"blockTimeIn\":11.5}")
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());

        Aircraft after = plane("user10");
        var logs = flightLogRepository.findByAircraft(after);
        assertEquals(1, logs.size());
        assertEquals(10.0, logs.get(0).getHobbsTimeOut(), 0.0001);
        assertEquals(11.5, logs.get(0).getHobbsTimeIn(), 0.0001);
        assertEquals(11.5, after.getHobbsTimeHours(), 0.0001);
    }

    @Test
    @WithMockUser(username = "user11", roles = {"USER"})
    void legacyUpdateHoursParamsStillWork() throws Exception {
        mockMvc.perform(post("/updateHours").param("newBlockTime", "250.5")
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());
        assertEquals(250.5, plane("user11").getHobbsTimeHours(), 0.0001);

        mockMvc.perform(post("/updateHours").param("blockTimeToAdd", "1.5")
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());
        assertEquals(252.0, plane("user11").getHobbsTimeHours(), 0.0001);
    }

    @Test
    @WithMockUser(username = "user12", roles = {"USER"})
    void newHobbsNameWinsOverLegacyName() throws Exception {
        mockMvc.perform(post("/updateHours").param("newHobbsTime", "300.0").param("newBlockTime", "1.0")
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());
        assertEquals(300.0, plane("user12").getHobbsTimeHours(), 0.0001);
    }
}
