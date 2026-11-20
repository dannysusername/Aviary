package com.example.AviaryService.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.StatusSheetItem;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.StatusSheetItemRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.support.TestFleet;

// Spec 007 through the real /update/{id} endpoint: the server is the truth
// for Due Date auto-fill and returns the saved row so the page shows it
// without a reload.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StatusSheetAutoFillTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AircraftRepository aircraft;
    @Autowired StatusSheetItemRepository items;

    private User owner;
    private Aircraft plane;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setUsername("autofill-" + UUID.randomUUID());
        owner.setPassword("x");
        owner = users.save(owner);
        plane = TestFleet.addAircraft(aircraft, users, owner, "N1", "", 0, 1000.0);
    }

    private StatusSheetItem row(Integer calValue, String calUnit, Double hours, String dueDate, String dueHours) {
        StatusSheetItem i = new StatusSheetItem();
        i.setItem("Oil change");
        i.setIsTitle(false);
        i.setUser(owner);
        i.setAircraft(plane);
        i.setCycleCalendarValue(calValue);
        i.setCycleCalendarUnit(calUnit);
        i.setCycleHours(hours);
        i.setDueDateDate(dueDate);
        i.setDueDateHours(dueHours);
        i.setStatusSheetOrder(0);
        return items.save(i);
    }

    private String body(StatusSheetItem i, String lastDate, String lastHours, String dueDate, String dueHours,
                        Boolean dateTouched, Boolean hoursTouched) {
        StringBuilder b = new StringBuilder("{\"item\":\"Oil change\",\"description\":\"\"");
        b.append(",\"cycleCalendarValue\":").append(i.getCycleCalendarValue());
        b.append(",\"cycleCalendarUnit\":").append(i.getCycleCalendarUnit() == null ? "null" : "\"" + i.getCycleCalendarUnit() + "\"");
        b.append(",\"cycleHours\":").append(i.getCycleHours());
        b.append(",\"lastDoneDate\":").append(q(lastDate));
        b.append(",\"lastDoneHours\":").append(q(lastHours));
        b.append(",\"dueDateDate\":").append(q(dueDate));
        b.append(",\"dueDateHours\":").append(q(dueHours));
        if (dateTouched != null) b.append(",\"dueDateTouched\":").append(dateTouched);
        if (hoursTouched != null) b.append(",\"dueHoursTouched\":").append(hoursTouched);
        return b.append('}').toString();
    }

    private static String q(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    private org.springframework.test.web.servlet.ResultActions save(StatusSheetItem i, String json) throws Exception {
        return mvc.perform(post("/update/" + i.getId()).with(user(owner.getUsername())).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void enteringLastDoneFillsDueDateAndReturnsItForTheScreen() throws Exception {
        StatusSheetItem i = row(1, "MONTHS", 100.0, null, null);
        save(i, body(i, "2026-10-30", "900", null, null, false, false))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dueDateDate").value("2026-11-30"))
            .andExpect(jsonPath("$.dueDateHours").value("1000.0"))
            .andExpect(jsonPath("$.lastDoneHours").value("900.0"))
            .andExpect(jsonPath("$.timeLeft").exists());

        StatusSheetItem saved = items.findById(i.getId()).orElseThrow();
        assertEquals("2026-11-30", saved.getDueDateDate());
        assertEquals("1000.0", saved.getDueDateHours());
        assertEquals("900.0", saved.getLastDoneHours());
        assertTrue(saved.getDueDateAuto());
        assertTrue(saved.getDueHoursAuto());
    }

    @Test
    void typedDueDateSurvivesALaterLastDoneChange() throws Exception {
        StatusSheetItem i = row(1, "MONTHS", null, null, null);
        save(i, body(i, "2026-10-30", null, "2027-03-01", null, true, false)).andExpect(status().isOk());
        save(i, body(i, "2026-12-05", null, "2027-03-01", null, false, false))
            .andExpect(jsonPath("$.dueDateDate").value("2027-03-01"));
        assertFalse(items.findById(i.getId()).orElseThrow().getDueDateAuto());
    }

    @Test
    void staleDueValueFromTheScreenDoesNotCountAsAnEdit() throws Exception {
        // Two quick saves: the second still carries the old (blank) due from
        // the page. Not touched, so the server keeps following Last Done.
        StatusSheetItem i = row(1, "MONTHS", null, null, null);
        save(i, body(i, "2026-10-30", null, null, null, false, false));
        save(i, body(i, "2026-11-05", null, null, null, false, false))
            .andExpect(jsonPath("$.dueDateDate").value("2026-12-05"));
    }

    @Test
    void olderDashboardWithoutFlagsSavesWhatItSent() throws Exception {
        StatusSheetItem i = row(1, "MONTHS", null, null, null);
        save(i, body(i, "2026-10-30", null, null, null, null, null)).andExpect(status().isOk());
        assertEquals(null, items.findById(i.getId()).orElseThrow().getDueDateDate());
    }

    @Test
    void nonNumericHoursAreRejectedButLegacyTextIsKept() throws Exception {
        StatusSheetItem i = row(null, null, 100.0, null, null);
        save(i, body(i, null, "abc", null, null, false, false))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Hours must be a number, e.g. 25.0"));

        StatusSheetItem legacy = row(null, null, null, null, "1200 TT");
        save(legacy, body(legacy, null, null, null, "1200 TT", false, false)).andExpect(status().isOk());
        assertEquals("1200 TT", items.findById(legacy.getId()).orElseThrow().getDueDateHours());
    }

    @Test
    void futureLastDoneAndHighHoursWarnButStillFill() throws Exception {
        StatusSheetItem i = row(1, "MONTHS", 100.0, null, null);
        String future = LocalDate.now().plusYears(1).toString();
        save(i, body(i, future, "5000", null, null, false, false))
            .andExpect(jsonPath("$.dueDateDate").value(LocalDate.now().plusYears(1).plusMonths(1).toString()))
            .andExpect(jsonPath("$.warnings[0]").value("Last Done is in the future. Check the date."))
            .andExpect(jsonPath("$.warnings[1]").value("Last Done hours are above current Time in Service. Check the hours."));
    }

    @Test
    void completeMaintenanceWritesHoursWithADecimal() throws Exception {
        StatusSheetItem i = row(null, null, 100.0, null, null);
        mvc.perform(post("/completeMaintenance/" + i.getId()).with(user(owner.getUsername())).with(csrf()))
            .andExpect(status().isOk());
        StatusSheetItem saved = items.findById(i.getId()).orElseThrow();
        assertEquals("1000.0", saved.getLastDoneHours());
        assertEquals("1100.0", saved.getDueDateHours());
        assertTrue(saved.getDueHoursAuto());
    }
}
