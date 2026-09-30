package com.example.AviaryService.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.AlertRecipient;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AlertRecipientRepository;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.UserRepository;

// Bad input should come back as a clear 4xx, never a 500 or a saved bad value.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InputValidationTest {

    private static final String USER = "validationuser";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ServiceTimelineRepository serviceTimelineRepository;
    @Autowired AlertRecipientRepository recipientRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        if (userRepository.findByUsername(USER) == null) {
            User u = new User();
            u.setUsername(USER);
            u.setPassword("x");
            u.setBlockTimeHours(100.0);
            u.setTimeInServiceHours(90.0);
            userRepository.save(u);
        }
    }

    // ── Registration ──

    @Test
    void register_rejectsEmptyPassword() throws Exception {
        mockMvc.perform(post("/register").with(csrf()).param("username", "newpilot").param("password", ""))
            .andExpect(status().isOk())
            .andExpect(view().name("register"))
            .andExpect(model().attributeExists("error"));
        assertNull(userRepository.findByUsername("newpilot"));
    }

    @Test
    void register_rejectsBlankUsername() throws Exception {
        mockMvc.perform(post("/register").with(csrf()).param("username", "   ").param("password", "Long-enough-1"))
            .andExpect(view().name("register"))
            .andExpect(model().attributeExists("error"));
        assertNull(userRepository.findByUsername("   "));
        assertNull(userRepository.findByUsername(""));
    }

    @Test
    void register_trimsAndAcceptsValidInput() throws Exception {
        mockMvc.perform(post("/register").with(csrf()).param("username", " goodpilot ").param("password", "Long-enough-1"))
            .andExpect(redirectedUrl("/login"));
        assertEquals("goodpilot", userRepository.findByUsername("goodpilot").getUsername());
    }

    // ── Hours ──

    @Test
    void updateHours_rejectsNaNAndNegative() throws Exception {
        mockMvc.perform(post("/updateHours").with(user(USER)).with(csrf()).param("newBlockTime", "NaN"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/updateHours").with(user(USER)).with(csrf()).param("newBlockTime", "-5"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/updateHours").with(user(USER)).with(csrf()).param("blockTimeToAdd", "-500"))
            .andExpect(status().isBadRequest());
        assertEquals(100.0, userRepository.findByUsername(USER).getBlockTimeHours(), 0.0001);
    }

    // ── Timeline rows ──

    @Test
    void addTimeline_tooLong_is400() throws Exception {
        String longItem = "x".repeat(300);
        mockMvc.perform(post("/dashboard").with(user(USER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"item\":\"" + longItem + "\",\"ajax\":\"true\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateTimeline_tooLong_is400WithMessage() throws Exception {
        ServiceTimeline t = new ServiceTimeline();
        t.setUser(userRepository.findByUsername(USER));
        t.setItem("Oil change");
        t.setIsTitle(false);
        serviceTimelineRepository.save(t);

        mockMvc.perform(post("/update/" + t.getId()).with(user(USER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"item\":\"" + "x".repeat(300) + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Item must be 255 characters or fewer."));
    }

    @Test
    void deleteMissingTimeline_is404() throws Exception {
        mockMvc.perform(delete("/delete/987654321").with(user(USER)).with(csrf()))
            .andExpect(status().isNotFound());
    }

    // ── Settings ──

    @Test
    void subscriptionSettings_nonNumeric_is400() throws Exception {
        mockMvc.perform(post("/subscription/settings").with(user(USER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pollIntervalDays\":\"x\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void alertRecipient_badChannel_hasFriendlyMessage() throws Exception {
        mockMvc.perform(post("/alerts/recipients").with(user(USER)).with(csrf())
                .param("channel", "FAX").param("destination", "a@example.com"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Pick Email or Text."));
    }

    @Test
    void confirmationEmails_areCappedPerHour() throws Exception {
        mockMvc.perform(post("/alerts/recipients").with(user(USER)).with(csrf())
                .param("channel", "EMAIL").param("destination", "mechanic@example.com"))
            .andExpect(status().isOk());
        AlertRecipient r = recipientRepository.findByUserOrderByCreatedAtAsc(userRepository.findByUsername(USER)).get(0);

        // The add used 1 of the 10 per hour; 9 more resends are allowed, then 429.
        for (int i = 0; i < 9; i++) {
            mockMvc.perform(post("/alerts/recipients/" + r.getId() + "/resend").with(user(USER)).with(csrf()))
                .andExpect(status().isOk());
        }
        mockMvc.perform(post("/alerts/recipients/" + r.getId() + "/resend").with(user(USER)).with(csrf()))
            .andExpect(status().isTooManyRequests());
    }

    // ── Login throttling ──

    @Test
    void login_locksAfterRepeatedWrongPasswords() throws Exception {
        User u = new User();
        u.setUsername("lockme");
        u.setPassword(passwordEncoder.encode("Right-password-1"));
        userRepository.save(u);

        mockMvc.perform(formLogin().user("lockme").password("Right-password-1"))
            .andExpect(redirectedUrl("/dashboard"));

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(formLogin().user("lockme").password("wrong" + i))
                .andExpect(redirectedUrl("/login?error"));
        }
        // Now even the right password is refused until the window passes.
        mockMvc.perform(formLogin().user("lockme").password("Right-password-1"))
            .andExpect(redirectedUrl("/login?locked"));
    }

    @Test
    void loginPage_rendersErrorAndLockedMessages() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/login").param("error", ""))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(org.hamcrest.Matchers.containsString("Incorrect username or password")));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/login").param("locked", ""))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(org.hamcrest.Matchers.containsString("Too many wrong passwords")));
    }
}
