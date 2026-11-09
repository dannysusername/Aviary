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
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
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
    @Autowired ServiceTimelineRepository timelineRepository;
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
        ServiceTimeline t = new ServiceTimeline();
        t.setUser(owner);
        t.setAircraft(a);
        t.setItem(name);
        t.setIsTitle(false);
        t.setTimelineOrder(0);
        t.setDueDateDate(dueDate);
        t.setDueDateHours(dueHours);
        timelineRepository.save(t);
    }

    @Test
    void oneAircraftReadsLikeBefore() {
        item(sr22, "ELT Battery", "2020-01-01", null);
        AlertDigest d = digestService.buildDigest(owner, alertService.getOrCreatePrefs(owner), false);
        assertEquals("N482DM — 1 overdue", d.subject());
        assertTrue(d.body().startsWith("Maintenance status for N482DM (Cirrus SR22)"), d.body());
        assertTrue(d.body().contains("Aircraft hours: Block Time"));
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
        assertTrue(body.startsWith("Maintenance status for your fleet (3 aircraft)"), body);
        int sr22At = body.indexOf("\nN482DM");
        int pa28At = body.indexOf("\nN731KR");
        assertTrue(sr22At > 0 && pa28At > sr22At, body);
        assertTrue(body.substring(pa28At).contains("OVERDUE   Oil Change"), body);
        assertTrue(body.substring(sr22At, pa28At).contains("ok        Magneto"), body);
        // An aircraft with nothing to report gets no section.
        assertFalse(body.contains("N999XX"), body);
        // What changed names the tail.
        assertTrue(body.contains("N731KR: Oil Change"), body);
        assertTrue(idle.getId() > 0);
    }
}
