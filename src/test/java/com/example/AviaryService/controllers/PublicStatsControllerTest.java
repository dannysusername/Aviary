package com.example.AviaryService.controllers;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.support.TestFleet;

// GET /api/public/stats: public, read-only, rate limited, CORS for
// DanielIbarra.com only, and never anything about one account.
// Contract: specs/004-public-aviary-stats/contracts/public-stats-api.md
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicStatsControllerTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired AircraftRepository aircraftRepository;

    @Test
    void publicWithoutLoginAndCreatesNoSession() throws Exception {
        MvcResult res = mvc.perform(get("/api/public/stats").with(r -> { r.setRemoteAddr("10.0.0.1"); return r; }))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("max-age=300")))
            .andExpect(jsonPath("$.users").isNumber())
            .andExpect(jsonPath("$.aircraft").isNumber())
            .andExpect(jsonPath("$.flights").isNumber())
            .andExpect(jsonPath("$.hoursFlown").isNumber())
            .andExpect(jsonPath("$.alertsSent").isNumber())
            .andExpect(jsonPath("$.signupsLast7Days").isNumber())
            .andExpect(jsonPath("$.signupsLast30Days").isNumber())
            .andExpect(jsonPath("$.recentSignups").isArray())
            .andExpect(jsonPath("$.generatedAt").isString())
            .andReturn();
        assertFalse(res.getResponse().getHeaderNames().contains("Set-Cookie"));
        assertNull(res.getRequest().getSession(false), "endpoint must not create a session");
    }

    @Test
    void corsAllowsDanielIbarraDotCom() throws Exception {
        mvc.perform(get("/api/public/stats").header("Origin", "https://danielibarra.com")
                .with(r -> { r.setRemoteAddr("10.0.0.2"); return r; }))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://danielibarra.com"));
    }

    @Test
    void corsRefusesOtherSites() throws Exception {
        mvc.perform(get("/api/public/stats").header("Origin", "https://evil.example")
                .with(r -> { r.setRemoteAddr("10.0.0.3"); return r; }))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void writesAreRefused() throws Exception {
        int code = mvc.perform(post("/api/public/stats").with(r -> { r.setRemoteAddr("10.0.0.4"); return r; }))
            .andReturn().getResponse().getStatus();
        assertNotEquals(200, code);
    }

    @Test
    void rateLimitedPerIp() throws Exception {
        for (int i = 0; i < PublicStatsController.MAX_PER_WINDOW; i++) {
            mvc.perform(get("/api/public/stats").with(r -> { r.setRemoteAddr("10.9.9.9"); return r; }))
                .andExpect(status().isOk());
        }
        mvc.perform(get("/api/public/stats").with(r -> { r.setRemoteAddr("10.9.9.9"); return r; }))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.error").value("rate_limited"));
        // A different visitor is unaffected.
        mvc.perform(get("/api/public/stats").with(r -> { r.setRemoteAddr("10.9.9.10"); return r; }))
            .andExpect(status().isOk());
    }

    // Constitution I: the public endpoint must not leak any one account's data.
    @Test
    void responseHasNoAccountDetails() throws Exception {
        User u = userRepository.save(new User("leakcheck-user", "x"));
        TestFleet.addAircraft(aircraftRepository, userRepository, u, "N0LEAK", "Leak Check Owner", 0, 0);

        String body = mvc.perform(get("/api/public/stats").with(r -> { r.setRemoteAddr("10.0.0.5"); return r; }))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        for (String forbidden : new String[] {"leakcheck-user", "N0LEAK", "Leak Check Owner",
                "DanielIbarra", "TomasIbarra", "\"id\"", "\"username\"", "\"password\"", "\"tailNumber\""}) {
            assertFalse(body.contains(forbidden), "response leaked " + forbidden + ": " + body);
        }
    }
}
