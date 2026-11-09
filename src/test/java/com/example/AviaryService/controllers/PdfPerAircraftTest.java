package com.example.AviaryService.controllers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.support.TestFleet;

// US6 / FR-018: the PDF covers the aircraft on screen and nothing else.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PdfPerAircraftTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired ServiceTimelineRepository timelineRepository;

    @Test
    void pdfHasOnlyTheChosenAircraft() throws Exception {
        User owner = new User();
        owner.setUsername("pdf-" + System.nanoTime());
        owner.setPassword("x");
        userRepository.save(owner);
        Aircraft a = TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N1AA", "", 0, 0);
        Aircraft b = TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N2BB", "", 0, 0);
        row(owner, a, "Alpha Annual");
        row(owner, b, "Bravo Oil Change");

        byte[] pdf = mvc.perform(get("/pdf").param("aircraftId", String.valueOf(b.getId())).with(user(owner.getUsername())))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();

        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        }
        assertTrue(text.contains("N2BB"), text);
        assertTrue(text.contains("Bravo Oil Change"), text);
        assertFalse(text.contains("N1AA"), text);
        assertFalse(text.contains("Alpha Annual"), text);
    }

    private void row(User owner, Aircraft a, String item) {
        ServiceTimeline t = new ServiceTimeline();
        t.setUser(owner);
        t.setAircraft(a);
        t.setItem(item);
        t.setIsTitle(false);
        t.setTimelineOrder(0);
        timelineRepository.save(t);
    }
}
