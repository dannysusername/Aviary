package com.example.AviaryService.services.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.StatusSheetItem;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.StatusSheetItemRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.support.TestFleet;

// US6 / FR-017: one alert email covers the whole fleet, grouped by tail
// number, and every hours item is judged against its own aircraft's hours.
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AlertDigestFleetTest {

    @Autowired AlertDigestService digestService;
    @Autowired AlertService alertService;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired StatusSheetItemRepository statusSheetItemRepository;
    @Autowired UserRepository userRepository;

    private User owner;
    private Aircraft sr22;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setUsername("digest-" + System.nanoTime());
        owner.setPassword("x");
        userRepository.save(owner);
        sr22 = TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N482DM", "Pat", 1500, 1400);
        sr22.setMakeModel("Cirrus SR22");
    }

    private void item(Aircraft a, String name, String dueDate, String dueHours) {
        StatusSheetItem t = new StatusSheetItem();
        t.setUser(owner);
        t.setAircraft(a);
        t.setItem(name);
        t.setIsTitle(false);
        t.setStatusSheetOrder(0);
        t.setDueDateDate(dueDate);
        t.setDueDateHours(dueHours);
        statusSheetItemRepository.save(t);
    }

    // Spec 013 research R3: most urgent first, days and hours compared by how
    // far into the owner's lead time they are (defaults 30 days / 10 hrs).
    @Test
    void dueItemsAreSortedMostUrgentFirst() {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        item(sr22, "Date 25 days", today.plusDays(25).toString(), null);  // 25/30 = 0.83
        item(sr22, "Hours 8 left", null, "1408");                         // 8/10 = 0.8 (TIS 1400)
        item(sr22, "Date 5 days", today.plusDays(5).toString(), null);    // 0.17
        item(sr22, "Overdue 3 days", today.minusDays(3).toString(), null);
        item(sr22, "Overdue 12 days", today.minusDays(12).toString(), null);
        item(sr22, "Fine", today.plusDays(300).toString(), null);

        AlertDigest d = digestService.buildDigest(owner, alertService.getOrCreatePrefs(owner), false);

        java.util.List<String> order = d.sections().get(0).due().stream().map(AlertDigest.DueItem::name).toList();
        assertEquals(java.util.List.of("Overdue 12 days", "Overdue 3 days", "Date 5 days", "Hours 8 left", "Date 25 days"), order);
        assertEquals(1, d.sections().get(0).onTrackCount());
    }

    @Test
    void oneAircraftReadsLikeBefore() {
        item(sr22, "ELT Battery", "2020-01-01", null);
        AlertDigest d = digestService.buildDigest(owner, alertService.getOrCreatePrefs(owner), false);
        assertEquals("N482DM — 1 overdue", d.subject());
        assertTrue(d.body().startsWith("N482DM (Cirrus SR22) — 1 overdue"), d.body());
        assertTrue(d.body().contains("Hours: Hobbs Time"));
    }

    @Test
    void twoAircraftShareOneDigestGroupedByTail() {
        Aircraft pa28 = TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N731KR", "Pat", 4200, 4100);
        Aircraft idle = TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N999XX", "Pat", 0, 0);
        item(sr22, "ELT Battery", "2020-01-01", null);
        // Due at 2000 hrs: fine for the SR22 (1400), overdue for the PA-28 (4100).
        item(sr22, "Magneto", null, "2000");
        item(pa28, "Oil Change", null, "2000");

        AlertDigest d = digestService.buildDigest(owner, alertService.getOrCreatePrefs(owner), false);

        assertEquals(2, d.overdueCount());
        assertEquals("Your fleet — 2 overdue", d.subject());
        String body = d.body();
        assertTrue(body.startsWith("Your fleet — 2 overdue"), body);
        int sr22At = body.indexOf("\nN482DM");
        int pa28At = body.indexOf("\nN731KR");
        assertTrue(sr22At > 0 && pa28At > sr22At, body);
        assertTrue(body.substring(pa28At).contains("  • Oil Change —"), body);
        // Spec 013: an item that's fine isn't listed, it's only counted.
        assertFalse(body.contains("Magneto"), body);
        // An aircraft with nothing due gets no section, just the on-track line.
        assertFalse(body.contains("\nN999XX ("), body);
        assertTrue(body.contains("N999XX: all items on track."), body);
        assertTrue(idle.getId() > 0);
    }
}
