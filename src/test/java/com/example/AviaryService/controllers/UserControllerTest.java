package com.example.AviaryService.controllers;

import com.example.AviaryService.entity.DescriptionOption;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.DescriptionOptionRepository;
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

import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        // Clear existing data to avoid duplicates and foreign key violations
        descriptionOptionRepository.deleteAll();
        serviceTimelineRepository.deleteAll();
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
                timeline.setCycle("Weekly");
                timeline.setLastDone("2025-10-01");
                timeline.setDueDate("2025-10-08");
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
}