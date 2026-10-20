package com.example.AviaryService.controllers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ConnectionsControllerTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;

    @Test
    void pageShowsThisSitesMcpAddress() throws Exception {
        User u = new User();
        u.setUsername("connect-" + System.nanoTime());
        u.setPassword("x");
        userRepository.save(u);

        mvc.perform(get("/account/connections").with(user(u.getUsername())))
            .andExpect(status().isOk())
            .andExpect(view().name("connections"))
            .andExpect(model().attribute("mcpUrl", "http://localhost:8080/mcp"));
    }

    @Test
    void requiresLogin() throws Exception {
        mvc.perform(get("/account/connections"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
    }
}
