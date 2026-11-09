package com.example.AviaryService.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.support.TestFleet;

// POST/GET/DELETE /aircraft, GET /dashboard?aircraft=, and the info card per
// aircraft. See contracts/http-endpoints.md.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AircraftControllerTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired AircraftRepository aircraftRepository;

    private User owner;
    private Aircraft a;

    @BeforeEach
    void setUp() {
        owner = newUser("ctl-");
        a = TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N482DM", "Pat", 10, 9);
    }

    private User newUser(String prefix) {
        User u = new User();
        u.setUsername(prefix + System.nanoTime());
        u.setPassword("x");
        return userRepository.save(u);
    }

    private String json(String tail) {
        return "{\"tailNumber\":\"" + tail + "\",\"makeModel\":\"Piper PA-28\"}";
    }

    // ---- POST /aircraft (US1) ----

    @Test
    void createReturns201WithRedirect() throws Exception {
        mvc.perform(post("/aircraft").with(user(owner.getUsername())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json("n 731 kr")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.redirect").value(org.hamcrest.Matchers.startsWith("/dashboard?aircraft=")));
        assertEquals(2, aircraftRepository.countByUser(owner));
    }

    @Test
    void createErrorsNameTheField() throws Exception {
        mvc.perform(post("/aircraft").with(user(owner.getUsername())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json("")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field").value("tailNumber"))
            .andExpect(jsonPath("$.message").value("Enter a tail number."));
        mvc.perform(post("/aircraft").with(user(owner.getUsername())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json("N482 DM")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("You already have an aircraft with this tail number."));
        mvc.perform(post("/aircraft").with(user(owner.getUsername())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json("N1!")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Use letters, numbers, spaces, or dashes."));
    }

    @Test
    void listShowsTheFleet() throws Exception {
        TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N731KR", "", 0, 0);
        mvc.perform(get("/aircraft").with(user(owner.getUsername())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].displayName").value("N482DM"))
            .andExpect(jsonPath("$[0].selected").value(true))
            .andExpect(jsonPath("$[1].displayName").value("N731KR"));
    }

    // ---- GET /dashboard?aircraft= (US3) ----

    @Test
    void dashboardShowsAndRemembersTheChosenAircraft() throws Exception {
        Aircraft b = TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N731KR", "", 0, 0);
        mvc.perform(get("/dashboard").param("aircraft", String.valueOf(b.getId())).with(user(owner.getUsername())))
            .andExpect(status().isOk())
            .andExpect(model().attribute("aircraftId", b.getId()))
            .andExpect(model().attribute("tailNumber", "N731KR"));
        assertEquals(b.getId(), userRepository.findById(owner.getId()).orElseThrow().getLastViewedAircraft().getId());
        mvc.perform(get("/dashboard").with(user(owner.getUsername())))
            .andExpect(model().attribute("aircraftId", b.getId()));
    }

    @Test
    void anotherAccountsAircraftIs404AndNotRemembered() throws Exception {
        User other = newUser("other-");
        Aircraft theirs = TestFleet.addAircraft(aircraftRepository, userRepository, other, "N9ZZ", "", 0, 0);
        mvc.perform(get("/dashboard").param("aircraft", String.valueOf(theirs.getId())).with(user(owner.getUsername())))
            .andExpect(status().isNotFound());
        assertEquals(a.getId(), userRepository.findById(owner.getId()).orElseThrow().getLastViewedAircraft().getId());
    }

    @Test
    void removedLastViewedFallsBackToFirst() throws Exception {
        Aircraft b = TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N731KR", "", 0, 0);
        owner.setLastViewedAircraft(b);
        userRepository.save(owner);
        mvc.perform(delete("/aircraft/" + b.getId()).with(user(owner.getUsername())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":\"N731KR\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/dashboard").with(user(owner.getUsername())))
            .andExpect(model().attribute("aircraftId", a.getId()));
    }

    // ---- DELETE /aircraft/{id} (US5) ----

    @Test
    void deleteRules() throws Exception {
        mvc.perform(delete("/aircraft/" + a.getId()).with(user(owner.getUsername())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":\"N482DM\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("You can't remove your only aircraft."));

        Aircraft b = TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N731KR", "", 0, 0);
        mvc.perform(delete("/aircraft/" + b.getId()).with(user(owner.getUsername())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":\"nope\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Type the tail number to confirm."));

        User other = newUser("other-");
        TestFleet.addAircraft(aircraftRepository, userRepository, other);
        mvc.perform(delete("/aircraft/" + b.getId()).with(user(other.getUsername())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":\"N731KR\"}"))
            .andExpect(status().isNotFound());
        assertTrue(aircraftRepository.findById(b.getId()).isPresent());

        mvc.perform(delete("/aircraft/" + b.getId()).with(user(owner.getUsername())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":\"n731kr\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.redirect").value("/dashboard?aircraft=" + a.getId()));
        assertFalse(aircraftRepository.findById(b.getId()).isPresent());
    }

    // ---- info card per aircraft (US5) ----

    @Test
    void infoCardEditsOnlyTheAircraftOnScreen() throws Exception {
        Aircraft b = TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N731KR", "", 0, 0);
        mvc.perform(post("/updateUserInfo").with(user(owner.getUsername())).with(csrf())
                .header("X-Aircraft-Id", b.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"makeModel\":\"Piper PA-28\"}"))
            .andExpect(status().isOk());
        assertEquals("Piper PA-28", aircraftRepository.findById(b.getId()).orElseThrow().getMakeModel());
        assertEquals("", aircraftRepository.findById(a.getId()).orElseThrow().getMakeModel());

        mvc.perform(post("/updateUserInfo").with(user(owner.getUsername())).with(csrf())
                .header("X-Aircraft-Id", b.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"tailNumber\":\"n482dm\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("You already have an aircraft with this tail number."));
        assertEquals("N731KR", aircraftRepository.findById(b.getId()).orElseThrow().getTailNumber());
    }
}
