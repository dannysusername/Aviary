package com.example.AviaryService.controllers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.AlertRecipient;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AlertPreferenceRepository;
import com.example.AviaryService.repositories.AlertRecipientRepository;
import com.example.AviaryService.repositories.AlertSendLogRepository;
import com.example.AviaryService.repositories.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional // roll back per test so alert_* rows don't leak into other classes
class AlertControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AlertRecipientRepository recipientRepository;
    @Autowired AlertPreferenceRepository preferenceRepository;
    @Autowired AlertSendLogRepository sendLogRepository;

    @BeforeEach
    void setUp() {
        // No deleteAll -- @Transactional isolates each test. The mock auth below
        // resolves "alertuser" against the DB, so the row must use that exact name.
        if (userRepository.findByUsername("alertuser") == null) {
            User u = new User();
            u.setUsername("alertuser");
            u.setPassword("x");
            u.setTailNumber("N999AV");
            u.setTimeInServiceHours(500.0);
            userRepository.save(u);
        }
    }

    @Test
    void getPreferences_defaultsToDisabled() throws Exception {
        // fresh user has no maintenance items yet -> readiness reports what's missing
        mockMvc.perform(get("/alerts/preferences").with(user("alertuser")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.checkHour").value(7))
            .andExpect(jsonPath("$.readiness.ready").value(false))
            .andExpect(jsonPath("$.readiness.missing[0]").value("Add at least one maintenance item"));
    }

    @Test
    void addListDeleteRecipient_roundTrip() throws Exception {
        mockMvc.perform(post("/alerts/recipients").with(user("alertuser")).with(csrf())
                .param("channel", "EMAIL").param("destination", "shop@example.com").param("label", "Shop"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(get("/alerts/recipients").with(user("alertuser")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recipients[0].destination").value("shop@example.com"));

        long id = recipientRepository.findAll().get(0).getId();
        mockMvc.perform(delete("/alerts/recipients/" + id).with(user("alertuser")).with(csrf()))
            .andExpect(status().isOk());
    }

    @Test
    void addRecipient_badEmail_is400() throws Exception {
        mockMvc.perform(post("/alerts/recipients").with(user("alertuser")).with(csrf())
                .param("channel", "EMAIL").param("destination", "garbage"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updatePreferences_enable() throws Exception {
        mockMvc.perform(put("/alerts/preferences").with(user("alertuser")).with(csrf())
                .contentType("application/json")
                .content("{\"enabled\":true,\"checkHour\":6,\"leadTimeDays\":45,\"leadTimeHours\":15,\"overdueRenudgeDays\":7}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.checkHour").value(6))
            .andExpect(jsonPath("$.leadTimeDays").value(45));
    }

    @Test
    void sendNow_ok_evenWithNoRecipients() throws Exception {
        mockMvc.perform(post("/alerts/send-now").with(user("alertuser")).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sent").value(0));
    }

    @Test
    void confirmLink_isPublic_andRendersHtml() throws Exception {
        User u = userRepository.findByUsername("alertuser");
        AlertRecipient r = new AlertRecipient(u, AlertRecipient.Channel.EMAIL, "c@example.com", null, "tok-abc-123");
        recipientRepository.save(r);

        // Opening the link (what a mail scanner does) only shows a button...
        mockMvc.perform(get("/alerts/confirm").param("token", "tok-abc-123"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("method=\"post\"")));
        org.junit.jupiter.api.Assertions.assertEquals(AlertRecipient.Status.PENDING,
            recipientRepository.findByConfirmToken("tok-abc-123").orElseThrow().getStatusEnum());

        // ...pressing it (a POST, no session or CSRF token) confirms.
        mockMvc.perform(post("/alerts/confirm").param("token", "tok-abc-123"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("confirmed")));

        AlertRecipient reloaded = recipientRepository.findByConfirmToken("tok-abc-123").orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(AlertRecipient.Status.ACCEPTED, reloaded.getStatusEnum());
    }

    @Test
    void confirmLink_badToken_stillRendersHtml() throws Exception {
        mockMvc.perform(post("/alerts/confirm").param("token", "does-not-exist"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("not valid")));
        mockMvc.perform(get("/alerts/confirm").param("token", "<script>"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("not valid")));
    }
}
