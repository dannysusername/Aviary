package com.example.AviaryService.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.StatusSheetItem;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.StatusSheetItemRepository;
import com.example.AviaryService.repositories.UserRepository;

import java.util.List;

@Component
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final StatusSheetItemRepository statusSheetItemRepository;
    private final PasswordEncoder passwordEncoder;
    private final AircraftRepository aircraftRepository;

    public DataSeeder(UserRepository userRepository, StatusSheetItemRepository statusSheetItemRepository,
            PasswordEncoder passwordEncoder, AircraftRepository aircraftRepository) {
        this.userRepository = userRepository;
        this.statusSheetItemRepository = statusSheetItemRepository;
        this.passwordEncoder = passwordEncoder;
        this.aircraftRepository = aircraftRepository;
    }

    @Override
    public void run(String... args) throws Exception {

        // Idempotent: only seed once. Re-running (a second test context in the
        // same JVM, or an app restart against a persistent DB) would otherwise
        // try to insert DanielIbarra again and hit the username unique constraint.
        if (userRepository.findByUsername("DanielIbarra") != null) {
            return;
        }

        User Daniel = new User("DanielIbarra", passwordEncoder.encode("DI"));
        User Tomas  = new User("TomasIbarra",  passwordEncoder.encode("TI"));

        userRepository.save(Daniel);
        userRepository.save(Tomas);

        // One aircraft each, which every Status Sheet item row below belongs to.
        Aircraft danielPlane = firstAircraft(Daniel, 3000, 2500);
        Aircraft tomasPlane  = firstAircraft(Tomas, 3000, 2500);


        // Tomas — aircraft at ~2480 total hours, ~25 hr/month
        // constructor: (item, isTitle, description, cycleCalendarUnit, cycleCalendarValue, cycleHours, lastDoneDate, lastDoneHours, dueDateDate, dueDateHours, timeLeft, user)
        StatusSheetItem t1 = new StatusSheetItem("Annual Inspection",        false, "Inspect aircraft systems per FAR 91.409",   "MONTHS", 12,   null,  "2026-01-15", "2380", "2027-01-15", "2680", "7 months",  Tomas);
        StatusSheetItem t2 = new StatusSheetItem("100-Hour Inspection",      false, "Inspect per FAR 91.409(b)",                 null,     null, 100.0, "2026-04-10", "2400", "2026-08-15", "2500", "20 hours",  Tomas);
        StatusSheetItem t3 = new StatusSheetItem("Oil Change",               false, "Replace engine oil and filter",             null,     null, 50.0,  "2026-05-20", "2455", "2026-07-10", "2505", "25 hours",  Tomas);
        StatusSheetItem t4 = new StatusSheetItem("Spark Plug Inspection",    false, "Clean, gap, and rotate spark plugs",        null,     null, 100.0, "2026-04-10", "2400", "2026-08-15", "2500", "20 hours",  Tomas);
        StatusSheetItem t5 = new StatusSheetItem("ELT Battery Replacement",  false, "Replace ELT battery per TSO-C91a",          "MONTHS", 24,   null,  "2024-06-10", "2100", "2026-06-10", "2490", "Overdue",   Tomas);
        StatusSheetItem t6 = new StatusSheetItem("Transponder Test",         false, "Test per FAR 91.413",                       "MONTHS", 24,   null,  "2025-03-10", "2250", "2027-03-10", "2850", "9 months",  Tomas);
        StatusSheetItem t7 = new StatusSheetItem("Magneto Inspection",       false, "Inspect and time magnetos",                 null,     null, 500.0, "2025-01-15", "2000", "2027-01-01", "2500", "20 hours",  Tomas);
        StatusSheetItem t8 = new StatusSheetItem("Pitot-Static System Test", false, "Test per FAR 91.411",                       "MONTHS", 24,   null,  "2025-03-10", "2250", "2027-03-10", "2850", "9 months",  Tomas);

        t1.setStatusSheetOrder(1); t2.setStatusSheetOrder(2); t3.setStatusSheetOrder(3); t4.setStatusSheetOrder(4);
        t5.setStatusSheetOrder(5); t6.setStatusSheetOrder(6); t7.setStatusSheetOrder(7); t8.setStatusSheetOrder(8);

        // Daniel — aircraft at ~1850 total hours, ~20 hr/month
        StatusSheetItem d1 = new StatusSheetItem("Annual Inspection",         false, "Inspect aircraft systems per FAR 91.409",  "MONTHS", 12,  null,  "2026-02-01", "1780", "2027-02-01", "2020", "8 months",  Daniel);
        StatusSheetItem d2 = new StatusSheetItem("Oil Filter Replacement",    false, "Replace oil filter",                       null,     null, 50.0, "2026-05-15", "1820", "2026-08-01", "1870", "20 hours",  Daniel);
        StatusSheetItem d3 = new StatusSheetItem("Landing Gear Inspection",   false, "Inspect gear, doors, and actuators",       "MONTHS", 12,  null,  "2026-02-01", "1780", "2027-02-01", "2020", "8 months",  Daniel);
        StatusSheetItem d4 = new StatusSheetItem("VOR Check",                 false, "VOR accuracy check per FAR 91.171",        "DAYS",   30,  null,  "2026-05-25", "1838", "2026-06-24", "1854", "12 days",   Daniel);
        StatusSheetItem d5 = new StatusSheetItem("Brake Lining Inspection",   false, "Inspect brake pads and discs",             null,     null, 100.0, "2026-03-10", "1770", "2026-09-15", "1870", "20 hours",  Daniel);
        StatusSheetItem d6 = new StatusSheetItem("Propeller Inspection",      false, "Inspect blades, hub, and spinner",         "MONTHS", 12,  null,  "2026-02-01", "1780", "2027-02-01", "2020", "8 months",  Daniel);
        StatusSheetItem d7 = new StatusSheetItem("Fuel System Inspection",    false, "Inspect tanks, lines, and fuel caps",      "MONTHS", 12,  null,  "2026-02-01", "1780", "2027-02-01", "2020", "8 months",  Daniel);
        StatusSheetItem d8 = new StatusSheetItem("Control Cable Inspection",  false, "Inspect cables for wear, routing, tension","MONTHS", 12,  null,  "2026-02-01", "1780", "2027-02-01", "2020", "8 months",  Daniel);

        d1.setStatusSheetOrder(1); d2.setStatusSheetOrder(2); d3.setStatusSheetOrder(3); d4.setStatusSheetOrder(4);
        d5.setStatusSheetOrder(5); d6.setStatusSheetOrder(6); d7.setStatusSheetOrder(7); d8.setStatusSheetOrder(8);

        for (StatusSheetItem t : List.of(t1, t2, t3, t4, t5, t6, t7, t8)) t.setAircraft(tomasPlane);
        for (StatusSheetItem d : List.of(d1, d2, d3, d4, d5, d6, d7, d8)) d.setAircraft(danielPlane);

        statusSheetItemRepository.saveAll(List.of(t1, t2, t3, t4, t5, t6, t7, t8, d1, d2, d3, d4, d5, d6, d7, d8));
    }

    private Aircraft firstAircraft(User user, double hobbsTime, double timeInService) {
        Aircraft a = new Aircraft(user);
        a.setHobbsTimeHours(hobbsTime);
        a.setTimeInServiceHours(timeInService);
        a = aircraftRepository.save(a);
        user.setLastViewedAircraft(a);
        userRepository.save(user);
        return a;
    }
}
