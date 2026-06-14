package com.example.AviaryService.controllers;

import com.example.AviaryService.entity.DescriptionOption;
import com.example.AviaryService.entity.FlightLog;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
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

    @BeforeEach
    void setUp() {
        // Clear existing data to avoid duplicates and foreign key violations
        descriptionOptionRepository.deleteAll();
        serviceTimelineRepository.deleteAll();
        flightLogRepository.deleteAll();
        userRepository.deleteAll();

        // Create 50 users with 2 timelines each
        for (int i = 1; i <= 50; i++) {
            User user = new User();
            user.setUsername("user" + i);
            user.setPassword(passwordEncoder.encode("password" + i));
            userRepository.save(user);

            for (int j = 1; j <= 2; j++) {
                ServiceTimeline timeline = new ServiceTimeline();
                timeline.setUser(user);
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
                .andExpect(redirectedUrl("/dashboard"));

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
                .andExpect(redirectedUrl("/dashboard"));

        // Verify data in PostgreSQL
        User user = userRepository.findByUsername("user2");
        List<ServiceTimeline> timelines = serviceTimelineRepository.findByUserOrderByTimelineOrderAsc(user);
        assertTrue(timelines.stream().anyMatch(t -> t.getItem().equals("Item") && t.getDescription().equals("Custom Desc")));
    }

    @Test
    @WithMockUser(username = "user1", roles = {"USER"})
    void testUpdateHoursStampsManualSource() throws Exception {
        mockMvc.perform(post("/updateHours")
                .param("newTachTime", "123.4")
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());

        User user = userRepository.findByUsername("user1");
        assertTrue(user.getTachUpdatedAt() != null, "tachUpdatedAt should be set after a manual edit");
        assertTrue("manual".equals(user.getTachUpdatedSource()), "tach source should be 'manual'");
    }

    @Test
    @WithMockUser(username = "user2", roles = {"USER"})
    void testAddFlightLogStampsFlightlogSource() throws Exception {
        mockMvc.perform(post("/addflightlog")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAirport\":\"KAAA\",\"toAirport\":\"KBBB\",\"hobbsOut\":10.0,\"hobbsIn\":11.0,\"tachOut\":9.0,\"tachIn\":10.0}")
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());

        User user = userRepository.findByUsername("user2");
        assertTrue("flightlog".equals(user.getTachUpdatedSource()), "tach source should be 'flightlog'");
        assertTrue(user.getTachUpdatedAt() != null, "tachUpdatedAt should be set after a flight log");
    }

    // ── Regression tests for the meter-snapshot rewrite ───────────────────────
    // These pin down the three bugs from the original /addflightlog logic:
    //   1. Partial entry (e.g. only tachOut) used to wipe BOTH meters to 0.
    //   2. Manual edits used to be silently overwritten by log activity.
    //   3. Deleting a log used to set hours to 0 instead of reverting.

    @Test
    @WithMockUser(username = "user3", roles = {"USER"})
    void testAddFlightLogRejectsPartialPairAndPreservesHours() throws Exception {
        // Set up a user with manually-entered hours.
        User u = userRepository.findByUsername("user3");
        u.setHobbsHours(100.0);
        u.setHobbsManualBaseline(100.0);
        u.setTachHours(100.0);
        u.setTachManualBaseline(100.0);
        userRepository.save(u);

        // The original bug: posting a log with only tachOut filled in.
        mockMvc.perform(post("/addflightlog")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAirport\":\"KAAA\",\"toAirport\":\"KBBB\",\"tachOut\":500.0}")
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest());

        // Critical: the meters must NOT have been touched.
        User after = userRepository.findByUsername("user3");
        assertEquals(100.0, after.getHobbsHours(), 0.0001, "Hobbs hours must not be wiped by a rejected partial entry");
        assertEquals(100.0, after.getTachHours(), 0.0001, "Tach hours must not be wiped by a rejected partial entry");
        assertTrue(flightLogRepository.findByUser(after).isEmpty(), "No partial log should have been saved");
    }

    @Test
    @WithMockUser(username = "user4", roles = {"USER"})
    void testManualBaselineActsAsFloorAgainstLowerLogReadings() throws Exception {
        // User manually claims 200 hours on the airframe.
        User u = userRepository.findByUsername("user4");
        u.setHobbsHours(200.0);
        u.setHobbsManualBaseline(200.0);
        u.setTachHours(200.0);
        u.setTachManualBaseline(200.0);
        userRepository.save(u);

        // Add a complete log whose hobbsIn=50 is well BELOW the manual floor.
        mockMvc.perform(post("/addflightlog")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"hobbsOut\":49.5,\"hobbsIn\":50.0,\"tachOut\":49.5,\"tachIn\":50.0}")
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());

        User after = userRepository.findByUsername("user4");
        assertEquals(200.0, after.getHobbsHours(), 0.0001, "Manual baseline must not be overwritten by a lower log reading");
        assertEquals(200.0, after.getTachHours(), 0.0001, "Manual baseline must not be overwritten by a lower log reading");
    }

    @Test
    @WithMockUser(username = "user5", roles = {"USER"})
    void testDeleteFlightLogRevertsToBaseline() throws Exception {
        // Start at a manual baseline of 100.
        User u = userRepository.findByUsername("user5");
        u.setHobbsHours(100.0);
        u.setHobbsManualBaseline(100.0);
        u.setTachHours(100.0);
        u.setTachManualBaseline(100.0);
        userRepository.save(u);

        // Add a log that raises hobbs to 150 and tach to 145.
        mockMvc.perform(post("/addflightlog")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"hobbsOut\":100.0,\"hobbsIn\":150.0,\"tachOut\":100.0,\"tachIn\":145.0}")
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());

        User afterAdd = userRepository.findByUsername("user5");
        assertEquals(150.0, afterAdd.getHobbsHours(), 0.0001, "Hobbs should be raised by the log");
        assertEquals(145.0, afterAdd.getTachHours(), 0.0001, "Tach should be raised by the log");

        // Delete that log — meters must revert to the baseline, not crash to 0.
        List<FlightLog> logs = flightLogRepository.findByUser(afterAdd);
        assertEquals(1, logs.size());
        Long logId = logs.get(0).getId();

        mockMvc.perform(delete("/deleteflightlog/" + logId)
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());

        User afterDelete = userRepository.findByUsername("user5");
        assertEquals(100.0, afterDelete.getHobbsHours(), 0.0001, "Delete must revert Hobbs to the manual baseline");
        assertEquals(100.0, afterDelete.getTachHours(), 0.0001, "Delete must revert Tach to the manual baseline");
    }
}