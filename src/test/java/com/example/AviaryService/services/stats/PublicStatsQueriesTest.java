package com.example.AviaryService.services.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.FlightLog;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.FlightLogRepository;
import com.example.AviaryService.repositories.UserRepository;

import jakarta.persistence.EntityManager;

// The public stats queries against a real (H2) database. The seeders also put
// rows in this database, so totals are checked as before/after differences.
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PublicStatsQueriesTest {

    @Autowired UserRepository userRepository;
    @Autowired FlightLogRepository flightLogRepository;
    @Autowired EntityManager em;

    @Test
    void hoursFlownSkipsMissingAndBackwardsReadings() {
        double before = orZero(flightLogRepository.sumHoursFlown());
        User u = userRepository.save(new User("stats-hours-user", "x"));
        flightLogRepository.save(new FlightLog("KTMB", "KFLL", null, null, 101.5, 100.0, u)); // +1.5
        flightLogRepository.save(new FlightLog("KTMB", "KFLL", null, null, 50.0, 50.0, u));   // skipped: in == out
        flightLogRepository.save(new FlightLog("KTMB", "KFLL", null, null, 49.0, 50.0, u));   // skipped: in < out
        flightLogRepository.save(new FlightLog("KTMB", "KFLL", null, null, null, 10.0, u));   // skipped: missing
        assertEquals(1.5, orZero(flightLogRepository.sumHoursFlown()) - before, 1e-9);
    }

    @Test
    void newAccountGetsCreatedAtThatNeverChanges() {
        User u = userRepository.saveAndFlush(new User("stats-created-user", "x"));
        Instant created = u.getCreatedAt();
        assertNotNull(created);

        u.setPassword("y");
        userRepository.saveAndFlush(u);
        em.clear();
        assertEquals(created, userRepository.findById(u.getId()).orElseThrow().getCreatedAt());
    }

    @Test
    void accountsWithoutCreatedAtAreNeverListedOrCountedAsRecent() {
        User old = userRepository.saveAndFlush(new User("stats-legacy-user", "x"));
        // Simulate an account made before signup dates were recorded.
        em.createQuery("update User u set u.createdAt = null where u.id = :id")
                .setParameter("id", old.getId()).executeUpdate();
        em.clear();
        assertNull(userRepository.findById(old.getId()).orElseThrow().getCreatedAt());

        List<Instant> recent = userRepository.findRecentSignupTimes(PageRequest.of(0, 1000));
        assertFalse(recent.contains(null));
        long recentCount = userRepository.countByCreatedAtAfter(Instant.EPOCH);
        assertTrue(recentCount < userRepository.count(), "legacy account counts as a user but not as a dated signup");
    }

    @Test
    void recentSignupsNewestFirstAndCapped() {
        for (int i = 0; i < 12; i++) {
            User u = new User("stats-feed-user-" + i, "x");
            u.setCreatedAt(Instant.parse("2030-01-01T00:00:00Z").plusSeconds(i * 3600L));
            userRepository.save(u);
        }
        userRepository.flush();
        List<Instant> top = userRepository.findRecentSignupTimes(PageRequest.of(0, 10));
        assertEquals(10, top.size());
        assertEquals(Instant.parse("2030-01-01T11:00:00Z"), top.get(0));
        for (int i = 1; i < top.size(); i++) {
            assertTrue(!top.get(i).isAfter(top.get(i - 1)), "newest first");
        }
    }

    private static double orZero(Double d) {
        return d == null ? 0.0 : d;
    }
}
