package com.example.AviaryService.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.UserRepository;

import java.util.List;

@Component
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final ServiceTimelineRepository serviceTimelineRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(UserRepository userRepository, ServiceTimelineRepository serviceTimelineRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.serviceTimelineRepository = serviceTimelineRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) throws Exception {

        User Daniel = new User("DanielIbarra", passwordEncoder.encode("DI"), 3000, 2500);
        User Tomas  = new User("TomasIbarra",  passwordEncoder.encode("TI"), 3000, 2500);

        userRepository.save(Daniel);
        userRepository.save(Tomas);


        // Tomas — aircraft at ~2480 total hours, ~25 hr/month
        // constructor: (item, isTitle, description, cycleCalendarUnit, cycleCalendarValue, cycleHours, lastDoneDate, lastDoneHours, dueDateDate, dueDateHours, timeLeft, user)
        ServiceTimeline t1 = new ServiceTimeline("Annual Inspection",        false, "Inspect aircraft systems per FAR 91.409",   "MONTHS", 12,   null,  "2026-01-15", "2380", "2027-01-15", "2680", "7 months",  Tomas);
        ServiceTimeline t2 = new ServiceTimeline("100-Hour Inspection",      false, "Inspect per FAR 91.409(b)",                 null,     null, 100.0, "2026-04-10", "2400", "2026-08-15", "2500", "20 hours",  Tomas);
        ServiceTimeline t3 = new ServiceTimeline("Oil Change",               false, "Replace engine oil and filter",             null,     null, 50.0,  "2026-05-20", "2455", "2026-07-10", "2505", "25 hours",  Tomas);
        ServiceTimeline t4 = new ServiceTimeline("Spark Plug Inspection",    false, "Clean, gap, and rotate spark plugs",        null,     null, 100.0, "2026-04-10", "2400", "2026-08-15", "2500", "20 hours",  Tomas);
        ServiceTimeline t5 = new ServiceTimeline("ELT Battery Replacement",  false, "Replace ELT battery per TSO-C91a",          "MONTHS", 24,   null,  "2024-06-10", "2100", "2026-06-10", "2490", "Overdue",   Tomas);
        ServiceTimeline t6 = new ServiceTimeline("Transponder Test",         false, "Test per FAR 91.413",                       "MONTHS", 24,   null,  "2025-03-10", "2250", "2027-03-10", "2850", "9 months",  Tomas);
        ServiceTimeline t7 = new ServiceTimeline("Magneto Inspection",       false, "Inspect and time magnetos",                 null,     null, 500.0, "2025-01-15", "2000", "2027-01-01", "2500", "20 hours",  Tomas);
        ServiceTimeline t8 = new ServiceTimeline("Pitot-Static System Test", false, "Test per FAR 91.411",                       "MONTHS", 24,   null,  "2025-03-10", "2250", "2027-03-10", "2850", "9 months",  Tomas);

        t1.setTimelineOrder(1); t2.setTimelineOrder(2); t3.setTimelineOrder(3); t4.setTimelineOrder(4);
        t5.setTimelineOrder(5); t6.setTimelineOrder(6); t7.setTimelineOrder(7); t8.setTimelineOrder(8);

        // Daniel — aircraft at ~1850 total hours, ~20 hr/month
        ServiceTimeline d1 = new ServiceTimeline("Annual Inspection",         false, "Inspect aircraft systems per FAR 91.409",  "MONTHS", 12,  null,  "2026-02-01", "1780", "2027-02-01", "2020", "8 months",  Daniel);
        ServiceTimeline d2 = new ServiceTimeline("Oil Filter Replacement",    false, "Replace oil filter",                       null,     null, 50.0, "2026-05-15", "1820", "2026-08-01", "1870", "20 hours",  Daniel);
        ServiceTimeline d3 = new ServiceTimeline("Landing Gear Inspection",   false, "Inspect gear, doors, and actuators",       "MONTHS", 12,  null,  "2026-02-01", "1780", "2027-02-01", "2020", "8 months",  Daniel);
        ServiceTimeline d4 = new ServiceTimeline("VOR Check",                 false, "VOR accuracy check per FAR 91.171",        "DAYS",   30,  null,  "2026-05-25", "1838", "2026-06-24", "1854", "12 days",   Daniel);
        ServiceTimeline d5 = new ServiceTimeline("Brake Lining Inspection",   false, "Inspect brake pads and discs",             null,     null, 100.0, "2026-03-10", "1770", "2026-09-15", "1870", "20 hours",  Daniel);
        ServiceTimeline d6 = new ServiceTimeline("Propeller Inspection",      false, "Inspect blades, hub, and spinner",         "MONTHS", 12,  null,  "2026-02-01", "1780", "2027-02-01", "2020", "8 months",  Daniel);
        ServiceTimeline d7 = new ServiceTimeline("Fuel System Inspection",    false, "Inspect tanks, lines, and fuel caps",      "MONTHS", 12,  null,  "2026-02-01", "1780", "2027-02-01", "2020", "8 months",  Daniel);
        ServiceTimeline d8 = new ServiceTimeline("Control Cable Inspection",  false, "Inspect cables for wear, routing, tension","MONTHS", 12,  null,  "2026-02-01", "1780", "2027-02-01", "2020", "8 months",  Daniel);

        d1.setTimelineOrder(1); d2.setTimelineOrder(2); d3.setTimelineOrder(3); d4.setTimelineOrder(4);
        d5.setTimelineOrder(5); d6.setTimelineOrder(6); d7.setTimelineOrder(7); d8.setTimelineOrder(8);

        serviceTimelineRepository.saveAll(List.of(t1, t2, t3, t4, t5, t6, t7, t8, d1, d2, d3, d4, d5, d6, d7, d8));
    }
}
