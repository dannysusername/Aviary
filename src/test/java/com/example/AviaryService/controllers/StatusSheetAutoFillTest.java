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

    // ── Roll-forward box: "Reset to cycle" ─────────────────────────────

    @Test
    void previewOffersTheCycleDateWhenATypedDueDateDiffers() throws Exception {
        StatusSheetItem i = row(1, "MONTHS", null, null, null);
        save(i, body(i, "2020-12-01", null, "2021-03-15", null, true, false));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/statusSheet/" + i.getId() + "/cyclePreview")
                .with(user(owner.getUsername())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.canReset").value(true))
            .andExpect(jsonPath("$.dueDateDate").value("2021-01-01"));

        mvc.perform(post("/resetDueToCycle/" + i.getId()).with(user(owner.getUsername())).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dueDateDate").value("2021-01-01"))
            .andExpect(jsonPath("$.lastDoneDate").value("2020-12-01"));
        StatusSheetItem saved = items.findById(i.getId()).orElseThrow();
        assertEquals("2021-01-01", saved.getDueDateDate());
        assertTrue(saved.getDueDateAuto(), "after reset the Due Date follows the cycle again");

        // ...and keeps following it.
        save(i, body(saved, "2021-02-01", null, "2021-01-01", null, false, false))
            .andExpect(jsonPath("$.dueDateDate").value("2021-03-01"));
    }

    @Test
    void previewIsGrayedWhenNothingToReset() throws Exception {
        StatusSheetItem i = row(1, "MONTHS", null, null, null);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/statusSheet/" + i.getId() + "/cyclePreview")
                .with(user(owner.getUsername())))
            .andExpect(jsonPath("$.canReset").value(false))
            .andExpect(jsonPath("$.reason").value("Enter Last Done first"));

        save(i, body(i, "2020-12-01", null, null, null, false, false));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/statusSheet/" + i.getId() + "/cyclePreview")
                .with(user(owner.getUsername())))
            .andExpect(jsonPath("$.canReset").value(false))
            .andExpect(jsonPath("$.reason").value("Due Date already matches the cycle"));
        mvc.perform(post("/resetDueToCycle/" + i.getId()).with(user(owner.getUsername())).with(csrf()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void resetHandlesBothPartsAndLegacyHoursCountAsMatching() throws Exception {
        StatusSheetItem i = row(1, "MONTHS", 100.0, "2021-03-15", "1300");
        i.setLastDoneDate("2020-12-01");
        i.setLastDoneHours("1200");
        items.save(i);
        mvc.perform(post("/resetDueToCycle/" + i.getId()).with(user(owner.getUsername())).with(csrf()))
            .andExpect(jsonPath("$.dueDateDate").value("2021-01-01"))
            .andExpect(jsonPath("$.dueDateHours").value("1300.0"));
    }

    @Test
    void anotherAccountCannotPreviewOrResetMyRow() throws Exception {
        StatusSheetItem mine = row(1, "MONTHS", null, "2021-03-15", null);
        User other = new User();
        other.setUsername("intruder-" + UUID.randomUUID());
        other.setPassword("x");
        other = users.save(other);
        TestFleet.addAircraft(aircraft, users, other);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/statusSheet/" + mine.getId() + "/cyclePreview")
                .with(user(other.getUsername())))
            .andExpect(status().isForbidden());
        mvc.perform(post("/resetDueToCycle/" + mine.getId()).with(user(other.getUsername())).with(csrf()))
            .andExpect(status().isForbidden());
        assertEquals("2021-03-15", items.findById(mine.getId()).orElseThrow().getDueDateDate());
    }

    // ── Spec 008: Mark complete rolls from the Due Date ───────────────

    private org.springframework.test.web.servlet.ResultActions preview(StatusSheetItem i, String params) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get("/statusSheet/" + i.getId() + "/completePreview" + (params == null ? "" : "?" + params))
            .with(user(owner.getUsername())));
    }

    private org.springframework.test.web.servlet.ResultActions complete(StatusSheetItem i, String doneDate, String doneHours,
                                                                        String expDate, String expHours) throws Exception {
        String json = "{\"doneDate\":" + q(doneDate) + ",\"doneHours\":" + q(doneHours)
            + ",\"expectedDueDate\":" + q(expDate) + ",\"expectedDueHours\":" + q(expHours) + "}";
        return mvc.perform(post("/completeMaintenance/" + i.getId()).with(user(owner.getUsername())).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private StatusSheetItem rowWith(Integer cal, String unit, Double hrs, String lastDate, String lastHours,
                                    String dueDate, String dueHours) {
        StatusSheetItem i = row(cal, unit, hrs, dueDate, dueHours);
        i.setLastDoneDate(lastDate);
        i.setLastDoneHours(lastHours);
        return items.save(i);
    }

    @Test
    void boxStartsWithTheDueDateAndShowsTheNextOne() throws Exception {
        StatusSheetItem i = rowWith(1, "MONTHS", 100.0, "2026-11-01", "1200.0", "2026-12-01", "1300");
        preview(i, null)
            .andExpect(jsonPath("$.hasCalendar").value(true))
            .andExpect(jsonPath("$.hasHours").value(true))
            .andExpect(jsonPath("$.doneDate").value("2026-12-01"))
            .andExpect(jsonPath("$.doneHours").value("1300.0"))
            .andExpect(jsonPath("$.nextDueDate").value("2027-01-01"))
            .andExpect(jsonPath("$.nextDueHours").value("1400.0"))
            .andExpect(jsonPath("$.expectedDueDate").value("2026-12-01"))
            .andExpect(jsonPath("$.canSave").value(true));
        preview(i, "doneDate=2026-11-20&doneHours=1290.5")
            .andExpect(jsonPath("$.nextDueDate").value("2026-12-20"))
            .andExpect(jsonPath("$.nextDueHours").value("1390.5"));
    }

    @Test
    void markCompleteRollsFromTheDueDate() throws Exception {
        StatusSheetItem i = rowWith(1, "MONTHS", 100.0, "2026-11-01", "1200.0", "2026-12-01", "1300.0");
        complete(i, "2026-12-01", "1300.0", "2026-12-01", "1300.0")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lastDoneDate").value("2026-12-01"))
            .andExpect(jsonPath("$.lastDoneHours").value("1300.0"))
            .andExpect(jsonPath("$.dueDateDate").value("2027-01-01"))
            .andExpect(jsonPath("$.dueDateHours").value("1400.0"));
        StatusSheetItem saved = items.findById(i.getId()).orElseThrow();
        assertTrue(saved.getDueDateAuto());
        assertTrue(saved.getDueHoursAuto());
    }

    @Test
    void secondRollWithTheSameBoxIsRefusedAndChangesNothing() throws Exception {
        // The old bug: a repeat click "succeeded" with a toast but changed
        // nothing. Now a repeat from the same box is refused with 409.
        StatusSheetItem i = rowWith(1, "MONTHS", null, "2026-11-01", null, "2026-12-01", null);
        complete(i, "2026-12-01", null, "2026-12-01", null).andExpect(status().isOk());
        complete(i, "2026-12-01", null, "2026-12-01", null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("This row changed. Open it again to review."));
        assertEquals("2027-01-01", items.findById(i.getId()).orElseThrow().getDueDateDate());

        // A fresh box previews the next cycle, and that roll works.
        preview(i, null).andExpect(jsonPath("$.doneDate").value("2027-01-01"));
        complete(i, "2027-01-01", null, "2027-01-01", null)
            .andExpect(jsonPath("$.dueDateDate").value("2027-02-01"));
    }

    @Test
    void editedDoneDateIsUsed() throws Exception {
        StatusSheetItem i = rowWith(1, "MONTHS", null, "2026-11-01", null, "2026-12-01", null);
        complete(i, "2026-11-20", null, "2026-12-01", null)
            .andExpect(jsonPath("$.lastDoneDate").value("2026-11-20"))
            .andExpect(jsonPath("$.dueDateDate").value("2026-12-20"));
    }

    @Test
    void emptyHoursBoxDoesNotRollHours() throws Exception {
        StatusSheetItem i = rowWith(1, "MONTHS", 100.0, "2026-11-01", "1200.0", "2026-12-01", "1300.0");
        complete(i, "2026-12-01", "", "2026-12-01", "1300.0")
            .andExpect(jsonPath("$.dueDateDate").value("2027-01-01"))
            .andExpect(jsonPath("$.lastDoneHours").value("1200.0"))
            .andExpect(jsonPath("$.dueDateHours").value("1300.0"));
    }

    @Test
    void hoursOnlyAndDateOnlyCyclesOnlyMoveTheirPart() throws Exception {
        StatusSheetItem hrs = rowWith(null, null, 50.0, "2026-01-01", "900.0", null, "950.0");
        preview(hrs, null).andExpect(jsonPath("$.hasCalendar").value(false)).andExpect(jsonPath("$.doneDate").doesNotExist());
        complete(hrs, null, "950.0", null, "950.0")
            .andExpect(jsonPath("$.lastDoneDate").value("2026-01-01"))
            .andExpect(jsonPath("$.dueDateHours").value("1000.0"));

        StatusSheetItem cal = rowWith(12, "MONTHS", null, null, "5.0", "2026-12-01", "8.0");
        complete(cal, "2026-12-01", null, "2026-12-01", "8.0")
            .andExpect(jsonPath("$.dueDateDate").value("2027-12-01"))
            .andExpect(jsonPath("$.dueDateHours").value("8.0"));
    }

    @Test
    void rowWithNothingYetStartsWithTodayAndEmptyHours() throws Exception {
        StatusSheetItem i = rowWith(1, "MONTHS", 100.0, null, null, null, null);
        preview(i, null)
            .andExpect(jsonPath("$.doneDate").value(LocalDate.now().toString()))
            .andExpect(jsonPath("$.doneHours").doesNotExist());
    }

    @Test
    void badOrMissingValuesAreRefusedWithAMessage() throws Exception {
        StatusSheetItem i = rowWith(1, "MONTHS", 100.0, "2026-11-01", "1200.0", "2026-12-01", "1300.0");
        complete(i, "", "", "2026-12-01", "1300.0")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Enter when it was done."));
        complete(i, "0002-12-01", "", "2026-12-01", "1300.0")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Enter the date it was done"));
        complete(i, "", "abc", "2026-12-01", "1300.0")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Hours must be a number, e.g. 25.0"));
        preview(i, "doneDate=&doneHours=")
            .andExpect(jsonPath("$.canSave").value(false)).andExpect(jsonPath("$.reason").value("Enter when it was done."));
        assertEquals("2026-12-01", items.findById(i.getId()).orElseThrow().getDueDateDate());
    }

    @Test
    void olderPageWithAnEmptyBodyStillWorks() throws Exception {
        StatusSheetItem i = rowWith(1, "MONTHS", null, "2020-01-01", null, "2020-02-01", null);
        mvc.perform(post("/completeMaintenance/" + i.getId()).with(user(owner.getUsername())).with(csrf()))
            .andExpect(status().isOk());
        assertEquals(LocalDate.now().toString(), items.findById(i.getId()).orElseThrow().getLastDoneDate());
    }

    @Test
    void anotherAccountCannotPreviewOrCompleteMyRow() throws Exception {
        StatusSheetItem mine = rowWith(1, "MONTHS", null, "2026-11-01", null, "2026-12-01", null);
        User other = new User();
        other.setUsername("intruder2-" + UUID.randomUUID());
        other.setPassword("x");
        other = users.save(other);
        TestFleet.addAircraft(aircraft, users, other);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/statusSheet/" + mine.getId() + "/completePreview").with(user(other.getUsername())))
            .andExpect(status().isForbidden());
        mvc.perform(post("/completeMaintenance/" + mine.getId()).with(user(other.getUsername())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"doneDate\":\"2026-12-01\",\"expectedDueDate\":\"2026-12-01\"}"))
            .andExpect(status().isForbidden());
        assertEquals("2026-12-01", items.findById(mine.getId()).orElseThrow().getDueDateDate());
    }

    // ── Spec 010: add row fills the Due Date before Add ───────────────

    private org.springframework.test.web.servlet.ResultActions resolve(String json) throws Exception {
        return mvc.perform(post("/statusSheet/addRowResolve").with(user(owner.getUsername())).with(csrf())
            .header("X-Aircraft-Id", plane.getId())
            .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void addRowFillsDateAndHoursBeforeAdd() throws Exception {
        resolve("{\"lastDoneDate\":\"2026-10-30\",\"lastDoneHours\":\"1200.5\","
                + "\"cycleCalendarValue\":1,\"cycleCalendarUnit\":\"MONTHS\",\"cycleHours\":100}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dueDateDate").value("2026-11-30"))
            .andExpect(jsonPath("$.dueDateAuto").value(true))
            .andExpect(jsonPath("$.dueDateHours").value("1300.5"))
            .andExpect(jsonPath("$.dueHoursAuto").value(true))
            .andExpect(jsonPath("$.lastDoneHours").value("1200.5"));
    }

    @Test
    void addRowFillsWhenTheCycleIsSetAfterLastDone() throws Exception {
        resolve("{\"prevLastDoneDate\":\"2026-10-30\",\"lastDoneDate\":\"2026-10-30\","
                + "\"cycleCalendarValue\":1,\"cycleCalendarUnit\":\"MONTHS\"}")
            .andExpect(jsonPath("$.dueDateDate").value("2026-11-30"));
        resolve("{\"lastDoneDate\":\"2026-10-30\",\"cycleCalendarValue\":1}")       // no unit yet
            .andExpect(jsonPath("$.dueDateDate").doesNotExist());
    }

    @Test
    void addRowKeepsATypedDueDateAndARemovedOne() throws Exception {
        // Typed: stays when Last Done changes.
        resolve("{\"prevLastDoneDate\":\"2026-10-30\",\"prevCycleCalendarValue\":1,\"prevCycleCalendarUnit\":\"MONTHS\","
                + "\"lastDoneDate\":\"2026-12-05\",\"cycleCalendarValue\":1,\"cycleCalendarUnit\":\"MONTHS\","
                + "\"dueDateDate\":\"2027-06-01\",\"dueDateAuto\":false}")
            .andExpect(jsonPath("$.dueDateDate").value("2027-06-01"))
            .andExpect(jsonPath("$.dueDateAuto").value(false));
        // Removed: blank and the user's, stays blank with nothing changed...
        resolve("{\"prevLastDoneDate\":\"2026-10-30\",\"prevCycleCalendarValue\":1,\"prevCycleCalendarUnit\":\"MONTHS\","
                + "\"lastDoneDate\":\"2026-10-30\",\"cycleCalendarValue\":1,\"cycleCalendarUnit\":\"MONTHS\","
                + "\"dueDateDate\":\"\",\"dueDateTouched\":true}")
            .andExpect(jsonPath("$.dueDateDate").doesNotExist())
            .andExpect(jsonPath("$.dueDateAuto").value(false));
        // ...and fills again when Last Done changes.
        resolve("{\"prevLastDoneDate\":\"2026-10-30\",\"prevCycleCalendarValue\":1,\"prevCycleCalendarUnit\":\"MONTHS\","
                + "\"lastDoneDate\":\"2026-11-15\",\"cycleCalendarValue\":1,\"cycleCalendarUnit\":\"MONTHS\","
                + "\"dueDateDate\":\"\",\"dueDateAuto\":false}")
            .andExpect(jsonPath("$.dueDateDate").value("2026-12-15"));
    }

    @Test
    void addRowClearsAnAutoDueDateWhenLastDoneIsCleared() throws Exception {
        resolve("{\"prevLastDoneDate\":\"2026-10-30\",\"prevCycleCalendarValue\":1,\"prevCycleCalendarUnit\":\"MONTHS\","
                + "\"lastDoneDate\":\"\",\"cycleCalendarValue\":1,\"cycleCalendarUnit\":\"MONTHS\","
                + "\"dueDateDate\":\"2026-11-30\",\"dueDateAuto\":true}")
            .andExpect(jsonPath("$.dueDateDate").doesNotExist());
    }

    @Test
    void addRowNotesAndBadHours() throws Exception {
        String future = LocalDate.now().plusYears(1).toString();
        resolve("{\"lastDoneDate\":\"" + future + "\",\"lastDoneHours\":\"5000\",\"cycleHours\":100}")
            .andExpect(jsonPath("$.warnings[0]").value("Last Done is in the future. Check the date."))
            .andExpect(jsonPath("$.warnings[1]").value("Last Done hours are above current Time in Service. Check the hours."));
        resolve("{\"lastDoneHours\":\"abc\",\"cycleHours\":100}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Hours must be a number, e.g. 25.0"));
    }

    private org.springframework.test.web.servlet.ResultActions add(String json) throws Exception {
        return mvc.perform(post("/dashboard").with(user(owner.getUsername())).with(csrf())
            .header("X-Aircraft-Id", plane.getId())
            .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private StatusSheetItem newest() {
        return items.findByAircraftOrderByStatusSheetOrderAsc(plane).stream()
            .reduce((a, b) -> b).orElseThrow();
    }

    @Test
    void addedRowKeepsFollowingTheCycleWhenItWasAutoFilled() throws Exception {
        add("{\"item\":\"Oil\",\"isTitle\":\"false\",\"ajax\":\"true\",\"cycleCalendarValue\":\"1\",\"cycleCalendarUnit\":\"MONTHS\","
                + "\"lastDoneDate\":\"2026-10-30\",\"dueDateDate\":\"2026-11-30\",\"dueDateAuto\":\"true\"}")
            .andExpect(status().is2xxSuccessful());
        StatusSheetItem saved = newest();
        assertEquals("2026-11-30", saved.getDueDateDate());
        assertTrue(saved.getDueDateAuto(), "auto-filled in the add row stays auto after Add");
    }

    @Test
    void addedRowKeepsATypedOrRemovedDueDate() throws Exception {
        add("{\"item\":\"Typed\",\"isTitle\":\"false\",\"ajax\":\"true\",\"cycleCalendarValue\":\"1\",\"cycleCalendarUnit\":\"MONTHS\","
                + "\"lastDoneDate\":\"2026-10-30\",\"dueDateDate\":\"2027-06-01\",\"dueDateAuto\":\"false\"}");
        StatusSheetItem typed = newest();
        assertEquals("2027-06-01", typed.getDueDateDate());
        assertFalse(typed.getDueDateAuto());

        add("{\"item\":\"Removed\",\"isTitle\":\"false\",\"ajax\":\"true\",\"cycleCalendarValue\":\"1\",\"cycleCalendarUnit\":\"MONTHS\","
                + "\"lastDoneDate\":\"2026-10-30\",\"dueDateDate\":\"\",\"dueDateAuto\":\"false\"}");
        StatusSheetItem removed = newest();
        assertEquals(null, removed.getDueDateDate(), "removed in the add row stays removed after Add");
    }

    @Test
    void addRowResolveRefusesAnotherAccountsAircraft() throws Exception {
        User other = new User();
        other.setUsername("intruder3-" + UUID.randomUUID());
        other.setPassword("x");
        other = users.save(other);
        Aircraft theirs = TestFleet.addAircraft(aircraft, users, other);
        int code = mvc.perform(post("/statusSheet/addRowResolve").with(user(owner.getUsername())).with(csrf())
                .header("X-Aircraft-Id", theirs.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"lastDoneHours\":\"1\",\"cycleHours\":1}"))
            .andReturn().getResponse().getStatus();
        assertTrue(code == 403 || code == 404, "another account's aircraft must be refused, got " + code);
    }
}
