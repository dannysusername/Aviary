package com.example.AviaryService.controllers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.support.TestFleet;
import com.example.AviaryService.repositories.AircraftRepository;

// Spec 014 matrix D (+ C4): the alert settings API -- defaults, Off/Once/N,
// validation, the old key, time zone, and one owner never touching another.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertPreferencesApiTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired AircraftRepository aircraftRepository;

    private String owner;

    private String newOwner(String prefix) {
        User u = new User();
        u.setUsername(prefix + System.nanoTime());
        u.setPassword("x");
        userRepository.save(u);
        TestFleet.addAircraft(aircraftRepository, userRepository, u, "N1", "Pat", 0, 0);
        return u.getUsername();
    }

    @BeforeEach
    void setUp() {
        owner = newOwner("prefs-");
    }

    private org.springframework.test.web.servlet.ResultActions putPrefs(String who, String json) throws Exception {
        return mvc.perform(put("/alerts/preferences").with(user(who)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void d1_newOwnerGetsTheDefaults() throws Exception {
        mvc.perform(get("/alerts/preferences").with(user(owner)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.statusEvery").value("off"))
            .andExpect(jsonPath("$.dueSoonEvery").value(7))
            .andExpect(jsonPath("$.overdueEvery").value(3))
            .andExpect(jsonPath("$.timeZone").value("America/New_York"));
    }

    @Test
    void d2_offOnceAndDaysRoundTrip() throws Exception {
        putPrefs(owner, "{\"dueSoonEvery\":\"once\",\"overdueEvery\":\"off\",\"statusEvery\":14}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dueSoonEvery").value("once"))
            .andExpect(jsonPath("$.overdueEvery").value("off"))
            .andExpect(jsonPath("$.statusEvery").value(14));
        mvc.perform(get("/alerts/preferences").with(user(owner)))
            .andExpect(jsonPath("$.dueSoonEvery").value("once"))
            .andExpect(jsonPath("$.overdueEvery").value("off"))
            .andExpect(jsonPath("$.statusEvery").value(14));
    }

    @Test
    void d3_statusReportCannotBeOnce() throws Exception {
        putPrefs(owner, "{\"statusEvery\":\"once\"}").andExpect(status().isBadRequest());
        putPrefs(owner, "{\"dueSoonEvery\":\"weekly\"}").andExpect(status().isBadRequest());
        mvc.perform(get("/alerts/preferences").with(user(owner)))
            .andExpect(jsonPath("$.statusEvery").value("off"))
            .andExpect(jsonPath("$.dueSoonEvery").value(7));
    }

    @Test
    void d4_oneOwnerNeverChangesAnother() throws Exception {
        String other = newOwner("prefs-other-");
        putPrefs(other, "{\"dueSoonEvery\":2,\"overdueEvery\":1,\"statusEvery\":7}").andExpect(status().isOk());
        mvc.perform(get("/alerts/preferences").with(user(owner)))
            .andExpect(jsonPath("$.dueSoonEvery").value(7))
            .andExpect(jsonPath("$.overdueEvery").value(3))
            .andExpect(jsonPath("$.statusEvery").value("off"));
    }

    @Test
    void oldRenudgeKeyStillSetsOverdue() throws Exception {
        putPrefs(owner, "{\"overdueRenudgeDays\":5}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.overdueEvery").value(5));
    }

    @Test
    void c4_timeZoneIsValidated() throws Exception {
        mvc.perform(post("/alerts/time-zone").with(user(owner)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"timeZone\":\"Not/AZone\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/alerts/preferences").with(user(owner)))
            .andExpect(jsonPath("$.timeZone").value("America/New_York"));
        mvc.perform(post("/alerts/time-zone").with(user(owner)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"timeZone\":\"America/Chicago\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/alerts/preferences").with(user(owner)))
            .andExpect(jsonPath("$.timeZone").value("America/Chicago"));
    }
}
