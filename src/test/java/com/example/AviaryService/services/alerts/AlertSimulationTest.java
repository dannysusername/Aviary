package com.example.AviaryService.services.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.AlertPreference;
import com.example.AviaryService.entity.AlertRecipient;
import com.example.AviaryService.entity.StatusSheetItem;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AlertPreferenceRepository;
import com.example.AviaryService.repositories.AlertRecipientRepository;
import com.example.AviaryService.repositories.StatusSheetItemRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.services.dashboard.PdfExportService;
import com.example.AviaryService.services.messaging.EmailSender;
import com.example.AviaryService.services.messaging.SmsSender;
import com.example.AviaryService.support.MutableClock;
import com.example.AviaryService.support.TestFleet;

// Spec 014 matrix B: run the REAL scheduler once a day for N simulated days
// (a test clock moves forward) and record which days each owner got an email.
// Every case prints "<case> emails on days: [...]" so a failure shows the day.
// See specs/014-alert-reminder-frequency/contracts/schedule-rules.md.
@SpringBootTest
@ActiveProfiles("test")
@Import(AlertSimulationTest.TestClock.class)
class AlertSimulationTest {

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-01-05T00:00:00Z"));
        }
    }

    static final ZoneId NY = ZoneId.of("America/New_York");
    static final LocalDate DAY0 = LocalDate.of(2026, 1, 5); // a Monday, no DST change for 60+ days

    @Autowired MutableClock clock;
    @Autowired AlertScheduler scheduler;
    @Autowired UserRepository userRepository;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired StatusSheetItemRepository itemRepository;
    @Autowired AlertPreferenceRepository preferenceRepository;
    @Autowired AlertRecipientRepository recipientRepository;

    @MockitoBean EmailSender emailSender;
    @MockitoBean SmsSender smsSender;
    @MockitoBean PdfExportService pdfExportService;

    // destination -> (day, subject) of every email handed to the sender
    private final List<String[]> sends = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sends.clear();
        when(emailSender.isConfigured()).thenReturn(true);
        when(pdfExportService.generateFleetPdf(any(), any(), any())).thenReturn(new byte[] {1});
        doAnswer(inv -> {
            long day = ChronoUnit.DAYS.between(DAY0, LocalDate.ofInstant(clock.instant(), NY));
            sends.add(new String[] {inv.getArgument(0), String.valueOf(day), inv.getArgument(1)});
            return null;
        }).when(emailSender).sendPdf(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    // ---- fixtures ----

    private final class Owner {
        final User user;
        final Aircraft plane;
        final AlertPreference prefs;
        final String inbox;

        Owner(String name) {
            User u = new User();
            u.setUsername("sim-" + name + "-" + System.nanoTime());
            u.setPassword("x");
            user = userRepository.save(u);
            plane = TestFleet.addAircraft(aircraftRepository, userRepository, user, "N" + name.toUpperCase(), "Pat", 100, 100);
            AlertPreference p = new AlertPreference(user);
            p.setAlertsEnabled(true); // set directly: no baseline email, so day 0 is the first sweep
            p.setTimeZone(NY.getId());
            prefs = preferenceRepository.save(p);
            inbox = user.getUsername() + "@example.com";
            AlertRecipient r = new AlertRecipient(user, AlertRecipient.Channel.EMAIL, inbox, null, "tok-" + name);
            r.setStatusEnum(AlertRecipient.Status.ACCEPTED);
            recipientRepository.save(r);
        }

        StatusSheetItem item(String name, int dueOnDay) {
            StatusSheetItem t = new StatusSheetItem();
            t.setUser(user);
            t.setAircraft(plane);
            t.setItem(name);
            t.setIsTitle(false);
            t.setStatusSheetOrder(0);
            t.setDueDateDate(DAY0.plusDays(dueOnDay).toString());
            return itemRepository.save(t);
        }

        Owner every(int dueSoon, int overdue, int status) {
            AlertPreference p = preferenceRepository.findById(prefs.getId()).orElseThrow();
            p.setDueSoonEveryDays(dueSoon);
            p.setOverdueEveryDays(overdue);
            p.setStatusEveryDays(status);
            preferenceRepository.save(p);
            return this;
        }

        List<Integer> days() {
            return sends.stream().filter(s -> s[0].equals(inbox)).map(s -> Integer.parseInt(s[1])).toList();
        }

        List<String> subjects() {
            return sends.stream().filter(s -> s[0].equals(inbox)).map(s -> s[2]).toList();
        }
    }

    // One scheduler tick per day at the owner's 7:05 (the scheduler runs at :05).
    // `onDay` runs before that day's tick (e.g. to fix an item).
    private void run(int days, java.util.function.IntConsumer onDay, Duration driftPerDay) {
        for (int d = 0; d < days; d++) {
            if (onDay != null) onDay.accept(d);
            ZonedDateTime at = ZonedDateTime.of(DAY0.plusDays(d), LocalTime.of(7, 5), NY).minus(driftPerDay.multipliedBy(d));
            clock.set(at.toInstant());
            scheduler.tick();
        }
    }

    private void run(int days) {
        run(days, null, Duration.ZERO);
    }

    private static void print(String name, Owner o) {
        System.out.println(name + " emails on days: " + o.days());
    }

    // ---- matrix B ----

    @Test
    void b1_defaultsDueSoonThenOverdue() {
        Owner o = new Owner("b1");
        o.item("ELT Battery", 25); // lead 30 -> due soon from day 0; overdue from day 26
        run(40);
        print("B1", o);
        assertEquals(List.of(0, 7, 14, 21, 26, 29, 32, 35, 38), o.days());
    }

    @Test
    void b2_fixedItemStopsReminders() {
        Owner o = new Owner("b2");
        StatusSheetItem t = o.item("ELT Battery", 25);
        run(20, d -> {
            if (d == 10) {
                t.setDueDateDate(DAY0.plusDays(400).toString());
                itemRepository.save(t);
            }
        }, Duration.ZERO);
        print("B2", o);
        assertEquals(List.of(0, 7), o.days());
    }

    @Test
    void b3_nothingDueSendsNothing() {
        Owner o = new Owner("b3");
        o.item("Annual", 300);
        run(60);
        print("B3", o);
        assertEquals(List.of(), o.days());
    }

    @Test
    void b4_neverMoreThanOneEmailADay() {
        Owner o = new Owner("b4").every(1, 1, 1);
        o.item("Overdue thing", -5);
        o.item("Due soon thing", 20);
        run(10);
        print("B4", o);
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), o.days());
    }

    @Test
    void b5_weeklyStatusReport() {
        Owner o = new Owner("b5").every(7, 3, 7);
        o.item("Annual", 300);
        run(21);
        print("B5", o);
        assertEquals(List.of(0, 7, 14), o.days());
        assertTrue(o.subjects().stream().allMatch(s -> s.equals("NB5 — weekly status: all on track")), o.subjects().toString());
    }

    @Test
    void b6_dueSoonOffWaitsForOverdue() {
        Owner o = new Owner("b6").every(AlertSchedule.Frequency.OFF, 3, AlertSchedule.Frequency.OFF);
        o.item("Annual", 5); // due soon days 0-5, overdue from day 6
        run(13);
        print("B6", o);
        assertEquals(List.of(6, 9, 12), o.days());
    }

    @Test
    void b7_overdueOnce() {
        Owner o = new Owner("b7").every(7, AlertSchedule.Frequency.ONCE, AlertSchedule.Frequency.OFF);
        o.item("Annual", -1);
        run(10);
        print("B7", o);
        assertEquals(List.of(0), o.days());
    }

    @Test
    void b8_alertsOffSendsNothing() {
        Owner o = new Owner("b8");
        AlertPreference p = preferenceRepository.findById(o.prefs.getId()).orElseThrow();
        p.setAlertsEnabled(false);
        preferenceRepository.save(p);
        o.item("Annual", -1);
        run(10);
        print("B8", o);
        assertEquals(List.of(), o.days());
    }

    // FR-006: an existing owner's "re-nudge every 5 days" carries over.
    @Test
    void b9_existingRenudgeCarriesOver() {
        Owner o = new Owner("b9");
        AlertPreference p = preferenceRepository.findById(o.prefs.getId()).orElseThrow();
        p.setOverdueRenudgeDays(5);
        preferenceRepository.save(p);
        assertEquals(5, preferenceRepository.findById(o.prefs.getId()).orElseThrow().getOverdueEveryDays());
        o.item("Annual", -1);
        run(16);
        print("B9", o);
        assertEquals(List.of(0, 5, 10, 15), o.days());
    }

    // R4: the old rolling 24 hours dropped an email whenever today's check ran
    // a little earlier than yesterday's. One per calendar day can't drift.
    @Test
    void b10_dailyEmailsSurviveAnEarlierCheckTime() {
        Owner o = new Owner("b10").every(1, 1, 1);
        o.item("Annual", 300);
        run(10, null, Duration.ofSeconds(30));
        print("B10", o);
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), o.days());
    }
}
