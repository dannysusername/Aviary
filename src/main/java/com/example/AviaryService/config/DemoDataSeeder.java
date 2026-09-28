package com.example.AviaryService.config;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.example.AviaryService.entity.FlightLog;
import com.example.AviaryService.entity.FlightSuggestion;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.FlightLogRepository;
import com.example.AviaryService.repositories.FlightSuggestionRepository;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.UserRepository;

// Local-only demo account for eyeballing the dashboard: log in as
// demo / Demo-pass-123. Has section titles, items due at every Time Left scale
// (days, months, a year+, overdue, hours), a log book, and pending AeroAPI
// suggestions. Due dates are relative to the day it's created. Only runs under
// the local "h2" and "main" profiles -- never on Heroku or in tests.
// Delete the "demo" user to have it recreated fresh on the next start.
@Component
@Profile({"h2", "main"})
public class DemoDataSeeder implements CommandLineRunner {

    static final String USERNAME = "demo";
    static final String PASSWORD = "Demo-pass-123";

    private final UserRepository userRepository;
    private final ServiceTimelineRepository timelineRepository;
    private final FlightLogRepository flightLogRepository;
    private final FlightSuggestionRepository suggestionRepository;
    private final PasswordEncoder passwordEncoder;

    public DemoDataSeeder(UserRepository userRepository, ServiceTimelineRepository timelineRepository,
            FlightLogRepository flightLogRepository, FlightSuggestionRepository suggestionRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.timelineRepository = timelineRepository;
        this.flightLogRepository = flightLogRepository;
        this.suggestionRepository = suggestionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (userRepository.findByUsername(USERNAME) != null) {
            return;
        }

        LocalDate today = LocalDate.now();
        User demo = new User(USERNAME, passwordEncoder.encode(PASSWORD), 1532.4, 1418.7);
        demo.setMakeModel("Cirrus SR22");
        demo.setTailNumber("N482DM");
        demo.setOwnerName("Demo Pilot");
        demo.setMakeModelSN("3517");
        userRepository.save(demo);

        List<ServiceTimeline> rows = new ArrayList<>();
        rows.add(title("Engine", demo));
        rows.add(item("Oil Change", "Replace", null, null, 50.0,
                today.minusMonths(1), "1383.9", null, "1433.9", demo));                 // 15.2 hours left
        rows.add(item("Spark Plugs", "Inspect", null, null, 100.0,
                today.minusMonths(4), "1330.0", null, "1430.0", demo));                 // 11.3 hours left
        rows.add(item("Magneto Timing", "Test", "MONTHS", 12, 500.0,
                today.minusMonths(11).minusDays(18), "1100.0", today.plusDays(12), "1600.0", demo)); // 12 days + hours
        rows.add(title("Airframe", demo));
        rows.add(item("Annual Inspection", "Inspect", "MONTHS", 12, null,
                today.minusMonths(10).minusDays(15), null, today.plusDays(45), null, demo));   // 45 days
        rows.add(item("ELT Battery", "Replace", "MONTHS", 24, null,
                today.minusMonths(24).minusDays(12), null, today.minusDays(12), null, demo));  // 12 days overdue
        rows.add(item("Parachute Repack (CAPS)", "Overhaul", "YEARS", 10, null,
                today.minusYears(9).minusMonths(7), null, today.plusMonths(5).plusDays(12), null, demo)); // 5 mo 12 days
        rows.add(title("Avionics", demo));
        rows.add(item("Transponder Check", "Test", "MONTHS", 24, null,
                today.minusMonths(11), null, today.plusDays(400), null, demo));        // 1 yr 1 mo
        rows.add(item("Pitot-Static Check", "Test", "MONTHS", 24, null,
                today.minusMonths(20), null, today.plusMonths(4), null, demo));        // 4 mo
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).setTimelineOrder(i);
        }
        timelineRepository.saveAll(rows);

        // Log book: a chain of recent flights, oldest first, each picking up
        // where the last left off so the meters are consistent.
        String[][] legs = {
            {"KTMB", "KFLL", "1.4", "1.2"}, {"KFLL", "KTMB", "1.3", "1.1"},
            {"KTMB", "KAPF", "1.8", "1.6"}, {"KAPF", "KTMB", "1.7", "1.5"},
            {"KTMB", "KEYW", "2.1", "1.9"}, {"KEYW", "KTMB", "2.0", "1.8"},
        };
        double block = 1532.4 - sum(legs, 2);
        double service = 1418.7 - sum(legs, 3);
        Instant start = today.minusDays(20).atTime(14, 0).toInstant(ZoneOffset.UTC);
        for (String[] leg : legs) {
            double blockLen = Double.parseDouble(leg[2]);
            double serviceLen = Double.parseDouble(leg[3]);
            FlightLog log = new FlightLog(leg[0], leg[1], round(block + blockLen), round(block),
                round(service + serviceLen), round(service), demo);
            log.setSource("manual");
            log.setBlockTimeStart(start);
            log.setBlockTimeEnd(start.plus(minutes(blockLen)));
            log.setTimeInServiceStart(start.plus(Duration.ofMinutes(6)));
            log.setTimeInServiceEnd(start.plus(Duration.ofMinutes(6)).plus(minutes(serviceLen)));
            flightLogRepository.save(log);
            block += blockLen;
            service += serviceLen;
            start = start.plus(Duration.ofDays(3));
        }

        // AeroAPI suggestions waiting for review (flights detected by
        // registration that aren't in the log book yet), plus one dismissed.
        Instant s1 = today.minusDays(2).atTime(15, 10).toInstant(ZoneOffset.UTC);
        Instant s2 = today.minusDays(1).atTime(13, 40).toInstant(ZoneOffset.UTC);
        Instant s3 = today.minusDays(1).atTime(18, 5).toInstant(ZoneOffset.UTC);
        suggestionRepository.save(new FlightSuggestion(demo, "N482DM", "demo-fa-1", s1, s1.plus(Duration.ofMinutes(72)), "KTMB", "KORL"));
        suggestionRepository.save(new FlightSuggestion(demo, "N482DM", "demo-fa-2", s2, s2.plus(Duration.ofMinutes(65)), "KORL", "KSRQ"));
        suggestionRepository.save(new FlightSuggestion(demo, "N482DM", "demo-fa-3", s3, s3.plus(Duration.ofMinutes(88)), "KSRQ", "KTMB"));
        FlightSuggestion dismissed = new FlightSuggestion(demo, "N482DM", "demo-fa-0",
            today.minusDays(9).atTime(16, 0).toInstant(ZoneOffset.UTC),
            today.minusDays(9).atTime(16, 35).toInstant(ZoneOffset.UTC), "KTMB", "X51");
        dismissed.setStatus("dismissed");
        suggestionRepository.save(dismissed);
    }

    private static ServiceTimeline title(String name, User user) {
        return new ServiceTimeline(name, true, null, null, null, null, null, null, null, null, null, user);
    }

    private static ServiceTimeline item(String name, String description, String calUnit, Integer calValue,
            Double cycleHours, LocalDate lastDone, String lastDoneHours, LocalDate due, String dueHours, User user) {
        return new ServiceTimeline(name, false, description, calUnit, calValue, cycleHours,
            calUnit == null ? null : lastDone.toString(), lastDoneHours,
            due == null ? null : due.toString(), dueHours, null, user);
    }

    private static double sum(String[][] legs, int col) {
        double total = 0;
        for (String[] leg : legs) total += Double.parseDouble(leg[col]);
        return total;
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static Duration minutes(double hours) {
        return Duration.ofMinutes(Math.round(hours * 60));
    }
}
