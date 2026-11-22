package com.example.AviaryService.services.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.example.AviaryService.entity.AlertPreference;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.AlertPreferenceRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.support.MutableClock;
import com.example.AviaryService.support.TestFleet;

// Spec 014 matrix C: "Check daily at 7:00" is the owner's 7:00, not the
// server's. We tick the real scheduler at chosen UTC moments and look at
// whether (and for which local date) the owner was checked.
@SpringBootTest
@ActiveProfiles("test")
@Import(AlertSimulationTest.TestClock.class)
class AlertTimeZoneTest {

    @Autowired MutableClock clock;
    @Autowired AlertScheduler scheduler;
    @Autowired UserRepository userRepository;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired AlertPreferenceRepository preferenceRepository;

    private AlertPreference owner(String zone) {
        User u = new User();
        u.setUsername("tz-" + System.nanoTime());
        u.setPassword("x");
        userRepository.save(u);
        TestFleet.addAircraft(aircraftRepository, userRepository, u, "N1", "Pat", 0, 0);
        AlertPreference p = new AlertPreference(u);
        p.setAlertsEnabled(true);
        p.setCheckHour(7);
        p.setTimeZone(zone);
        return preferenceRepository.save(p);
    }

    private LocalDate checkedOn(AlertPreference p) {
        return preferenceRepository.findById(p.getId()).orElseThrow().getLastCheckedOn();
    }

    private void tickAt(String utc) {
        clock.set(Instant.parse(utc));
        scheduler.tick();
    }

    @Test
    void c1_newYorkSevenIsNoonUtcInWinterAndElevenInSummer() {
        AlertPreference winter = owner("America/New_York");
        tickAt("2026-01-15T07:05:00Z");          // 2:05 in New York
        assertNull(checkedOn(winter));
        tickAt("2026-01-15T12:05:00Z");          // 7:05 EST
        assertEquals(LocalDate.of(2026, 1, 15), checkedOn(winter));

        AlertPreference summer = owner("America/New_York");
        tickAt("2026-07-15T12:05:00Z");          // 8:05 EDT: not their hour
        assertNull(checkedOn(summer));
        tickAt("2026-07-16T11:05:00Z");          // 7:05 EDT
        assertEquals(LocalDate.of(2026, 7, 16), checkedOn(summer));
    }

    @Test
    void c2_tokyoSevenIsTheDayBeforeInUtc() {
        AlertPreference tokyo = owner("Asia/Tokyo");
        tickAt("2026-01-14T22:05:00Z");          // 7:05 on Jan 15 in Tokyo
        assertEquals(LocalDate.of(2026, 1, 15), checkedOn(tokyo));
    }

    @Test
    void c3_unknownZoneUsesNewYork() {
        AlertPreference none = owner(null);
        tickAt("2026-02-02T07:05:00Z");
        assertNull(checkedOn(none));
        tickAt("2026-02-02T12:05:00Z");
        assertEquals(LocalDate.of(2026, 2, 2), checkedOn(none));
    }
}
