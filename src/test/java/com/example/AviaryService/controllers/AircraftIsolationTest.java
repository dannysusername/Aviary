package com.example.AviaryService.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.FlightLogRepository;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.support.TestFleet;

// Constitution I: every endpoint that acts on "the current aircraft" acts on
// exactly the one the request names, and another account's aircraft is a 404
// with nothing read or changed. Also: two aircraft of the same owner never
// leak into each other (US1).
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AircraftIsolationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired ServiceTimelineRepository timelineRepository;
    @Autowired FlightLogRepository flightLogRepository;

    private User owner;
    private Aircraft a;
    private Aircraft b;
    private User intruder;

    @BeforeEach
    void setUp() {
        owner = newUser("iso-");
        a = TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N1AA", "Pat", 100, 100);
        b = TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N2BB", "Pat", 500, 500);
        intruder = newUser("intruder-");
        TestFleet.addAircraft(aircraftRepository, userRepository, intruder, "N3CC", "Eve", 0, 0);
    }

    private User newUser(String prefix) {
        User u = new User();
        u.setUsername(prefix + System.nanoTime());
        u.setPassword("x");
        return userRepository.save(u);
    }

    private ServiceTimeline row(Aircraft on, String item, int order, String dueHours, Double cycleHours) {
        ServiceTimeline t = new ServiceTimeline();
        t.setUser(owner);
        t.setAircraft(on);
        t.setItem(item);
        t.setIsTitle(false);
        t.setTimelineOrder(order);
        t.setDueDateHours(dueHours);
        t.setCycleHours(cycleHours);
        return timelineRepository.save(t);
    }

    // ---- separation between one owner's aircraft ----

    @Test
    void addedRowLandsOnlyOnTheNamedAircraft() throws Exception {
        mvc.perform(post("/dashboard").with(user(owner.getUsername())).with(csrf())
                .header("X-Aircraft-Id", b.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"item\":\"Oil\",\"ajax\":\"true\"}"))
            .andExpect(status().isOk());
        assertEquals(1, timelineRepository.findByAircraftOrderByTimelineOrderAsc(b).size());
        assertTrue(timelineRepository.findByAircraftOrderByTimelineOrderAsc(a).isEmpty());
    }

    @Test
    void flightRaisesOnlyItsAircraftsHours() throws Exception {
        mvc.perform(post("/addflightlog").with(user(owner.getUsername())).with(csrf())
                .header("X-Aircraft-Id", b.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"blockTimeOut\":500.0,\"blockTimeIn\":502.0,\"timeInServiceOut\":500.0,\"timeInServiceIn\":501.5}"))
            .andExpect(status().isOk());
        assertEquals(502.0, aircraftRepository.findById(b.getId()).orElseThrow().getBlockTimeHours(), 1e-9);
        assertEquals(100.0, aircraftRepository.findById(a.getId()).orElseThrow().getBlockTimeHours(), 1e-9);
        assertTrue(flightLogRepository.findByAircraft(a).isEmpty());
    }

    @Test
    void completeMaintenanceUsesTheRowsOwnAircraftHours() throws Exception {
        ServiceTimeline oil = row(a, "Oil", 0, "150", 50.0);
        mvc.perform(post("/completeMaintenance/" + oil.getId()).with(user(owner.getUsername())).with(csrf())
                .header("X-Aircraft-Id", b.getId())) // header ignored: the row decides
            .andExpect(status().isOk());
        ServiceTimeline after = timelineRepository.findById(oil.getId()).orElseThrow();
        // A is at 100 hrs (B is at 500): done at 100, next due at 150.
        assertEquals(100.0, Double.parseDouble(after.getLastDoneHours()), 1e-9);
        assertEquals(150.0, Double.parseDouble(after.getDueDateHours()), 1e-9);
    }

    @Test
    void reorderMixingAircraftIsRejectedAndSavesNothing() throws Exception {
        ServiceTimeline a1 = row(a, "A1", 0, null, null);
        ServiceTimeline a2 = row(a, "A2", 1, null, null);
        ServiceTimeline b1 = row(b, "B1", 0, null, null);
        mvc.perform(post("/updateOrder").with(user(owner.getUsername())).with(csrf())
                .header("X-Aircraft-Id", a.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("[" + a2.getId() + "," + b1.getId() + "," + a1.getId() + "]"))
            .andExpect(status().isBadRequest());
        assertEquals(0, timelineRepository.findById(a1.getId()).orElseThrow().getTimelineOrder());
        assertEquals(1, timelineRepository.findById(a2.getId()).orElseThrow().getTimelineOrder());

        mvc.perform(post("/updateOrder").with(user(owner.getUsername())).with(csrf())
                .header("X-Aircraft-Id", a.getId())
                .contentType(MediaType.APPLICATION_JSON).content("[" + a2.getId() + "," + a1.getId() + "]"))
            .andExpect(status().isOk());
        assertEquals(1, timelineRepository.findById(a1.getId()).orElseThrow().getTimelineOrder());
        assertEquals(0, timelineRepository.findById(b1.getId()).orElseThrow().getTimelineOrder());
    }

    @Test
    void severalAircraftAndNoIdIsChooseAnAircraft() throws Exception {
        mvc.perform(get("/flightlogs").with(user(owner.getUsername())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Choose an aircraft."));
    }

    // ---- another account's aircraft id: 404 everywhere ----

    private List<MockHttpServletRequestBuilder> currentAircraftEndpoints() {
        String json = MediaType.APPLICATION_JSON_VALUE;
        return List.of(
            post("/dashboard").contentType(json).content("{\"item\":\"x\",\"ajax\":\"true\"}"),
            post("/updateOrder").contentType(json).content("[]"),
            post("/updateUserInfo").contentType(json).content("{\"makeModel\":\"hacked\"}"),
            post("/updateHours").param("newBlockTime", "1"),
            get("/flightlogs"),
            post("/addflightlog").contentType(json).content("{\"blockTimeOut\":1.0,\"blockTimeIn\":2.0}"),
            multipart("/logbook/upload-csv").file(new MockMultipartFile("csvfile", "a.csv", "text/csv", new byte[0])),
            get("/pdf"),
            post("/pdf/email").param("recipient", "a@example.com"),
            post("/subscription/subscribe").contentType(json).content("{\"registration\":\"N1AA\"}"),
            post("/subscription/unsubscribe"),
            post("/subscription/settings").contentType(json).content("{\"pollIntervalDays\":1,\"preferredCheckHour\":3}"),
            post("/subscription/check-now"),
            delete("/subscription"),
            get("/flightsuggestions/all"));
    }

    @Test
    void anotherAccountsAircraftIdIs404ByHeader() throws Exception {
        for (MockHttpServletRequestBuilder req : currentAircraftEndpoints()) {
            mvc.perform(req.with(user(intruder.getUsername())).with(csrf()).header("X-Aircraft-Id", a.getId()))
                .andExpect(status().isNotFound());
        }
        assertUntouched();
    }

    @Test
    void anotherAccountsAircraftIdIs404ByParam() throws Exception {
        for (MockHttpServletRequestBuilder req : currentAircraftEndpoints()) {
            mvc.perform(req.with(user(intruder.getUsername())).with(csrf()).param("aircraftId", String.valueOf(a.getId())))
                .andExpect(status().isNotFound());
        }
        assertUntouched();
    }

    @Test
    void garbageIdIs404() throws Exception {
        mvc.perform(get("/flightlogs").with(user(owner.getUsername())).header("X-Aircraft-Id", "abc"))
            .andExpect(status().isNotFound());
        mvc.perform(get("/flightlogs").with(user(owner.getUsername())).header("X-Aircraft-Id", "-1"))
            .andExpect(status().isNotFound());
    }

    private void assertUntouched() {
        Aircraft after = aircraftRepository.findById(a.getId()).orElseThrow();
        assertEquals("", after.getMakeModel());
        assertEquals(100.0, after.getBlockTimeHours(), 1e-9);
        assertTrue(timelineRepository.findByAircraftOrderByTimelineOrderAsc(a).isEmpty());
        assertTrue(flightLogRepository.findByAircraft(a).isEmpty());
    }
}
