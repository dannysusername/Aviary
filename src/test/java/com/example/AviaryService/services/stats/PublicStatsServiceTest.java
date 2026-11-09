package com.example.AviaryService.services.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.AlertSendLogRepository;
import com.example.AviaryService.repositories.FlightLogRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.services.stats.PublicStatsSnapshot.SignupEntry;

// Caching and number-shaping rules of PublicStatsService, with the database
// mocked out. The real queries are covered by PublicStatsQueriesTest.
class PublicStatsServiceTest {

    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-10T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    UserRepository users = mock(UserRepository.class);
    AircraftRepository aircraft = mock(AircraftRepository.class);
    FlightLogRepository flights = mock(FlightLogRepository.class);
    AlertSendLogRepository alerts = mock(AlertSendLogRepository.class);
    TestClock clock = new TestClock();
    PublicStatsProperties props = new PublicStatsProperties(
            List.of("https://danielibarra.com"), ZoneId.of("America/New_York"), Duration.ofMinutes(15));
    PublicStatsService service;

    @BeforeEach
    void setUp() {
        when(users.count()).thenReturn(3L);
        when(aircraft.count()).thenReturn(4L);
        when(flights.count()).thenReturn(5L);
        when(flights.sumHoursFlown()).thenReturn(12.345);
        when(alerts.count()).thenReturn(2L);
        when(users.findRecentSignupTimes(any(Pageable.class))).thenReturn(List.of());
        service = new PublicStatsService(users, aircraft, flights, alerts, props, clock);
    }

    @Test
    void countsEveryAccountAndTotal() {
        PublicStatsSnapshot s = service.current();
        assertEquals(3, s.users());
        assertEquals(4, s.aircraft());
        assertEquals(5, s.flights());
        assertEquals(12.3, s.hoursFlown());
        assertEquals(2, s.alertsSent());
        assertEquals(clock.now, s.generatedAt());
    }

    @Test
    void emptyDatabaseGivesZerosNotNulls() {
        when(users.count()).thenReturn(0L);
        when(flights.sumHoursFlown()).thenReturn(null);
        PublicStatsSnapshot s = service.current();
        assertEquals(0, s.users());
        assertEquals(0.0, s.hoursFlown());
        assertTrue(s.recentSignups().isEmpty());
    }

    @Test
    void servesCachedSnapshotUntilTtlPasses() {
        PublicStatsSnapshot first = service.current();
        clock.now = clock.now.plus(Duration.ofMinutes(14));
        assertSame(first, service.current());
        verify(users, times(1)).count();

        clock.now = clock.now.plus(Duration.ofMinutes(1));
        PublicStatsSnapshot second = service.current();
        verify(users, times(2)).count();
        assertEquals(clock.now, second.generatedAt());
    }

    @Test
    void failedRefreshKeepsServingOldSnapshot() {
        PublicStatsSnapshot first = service.current();
        clock.now = clock.now.plus(Duration.ofMinutes(20));
        when(users.count()).thenThrow(new RuntimeException("db down"));
        assertSame(first, service.current());
    }

    @Test
    void failureWithNoSnapshotYetIsUnavailable() {
        when(users.count()).thenThrow(new RuntimeException("db down"));
        assertThrows(PublicStatsUnavailableException.class, service::current);
    }

    @Test
    void signupsAreDateOnlyInConfiguredZoneWithFixedLabel() {
        // 03:00 UTC on Nov 10 is still the evening of Nov 9 in New York.
        when(users.findRecentSignupTimes(any(Pageable.class)))
                .thenReturn(List.of(Instant.parse("2026-11-10T03:00:00Z")));
        List<SignupEntry> feed = service.current().recentSignups();
        assertEquals(List.of(new SignupEntry("New pilot joined", "2026-11-09")), feed);
    }

    @Test
    void asksForAtMostTenSignups() {
        List<Instant> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) ten.add(clock.now.minus(Duration.ofDays(i)));
        when(users.findRecentSignupTimes(any(Pageable.class))).thenAnswer(inv -> {
            assertEquals(10, ((Pageable) inv.getArgument(0)).getPageSize());
            return ten;
        });
        assertEquals(10, service.current().recentSignups().size());
    }

    @Test
    void signupWindowsAreSevenAndThirtyDays() {
        when(users.countByCreatedAtAfter(clock.now.minus(Duration.ofDays(7)))).thenReturn(1L);
        when(users.countByCreatedAtAfter(clock.now.minus(Duration.ofDays(30)))).thenReturn(6L);
        PublicStatsSnapshot s = service.current();
        assertEquals(1, s.signupsLast7Days());
        assertEquals(6, s.signupsLast30Days());
    }
}
