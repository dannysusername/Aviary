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

        User Daniel = new User("DanielIbarra", passwordEncoder.encode("DI"));
        User Tomas = new User("TomasIbarra", passwordEncoder.encode("TI"));

        userRepository.save(Daniel);
        userRepository.save(Tomas);

        // Tomas - 8 rows
        ServiceTimeline row1   = new ServiceTimeline("Annual Inspection",         false, "Inspect",  "12 Months",  "01/15/2026", "01/15/2027", null, Tomas);
        ServiceTimeline row2   = new ServiceTimeline("100-Hour Inspection",       false, "Inspect",  "100 Hours",  "12/01/2025", "02/10/2026", null, Tomas);
        ServiceTimeline row3   = new ServiceTimeline("Oil Change",                false, "Replace",  "50 Hours",   "11/20/2025", "12/30/2025", null, Tomas);
        ServiceTimeline row4   = new ServiceTimeline("Spark Plug Inspection",     false, "Inspect",  "100 Hours",  "12/01/2025", "02/10/2026", null, Tomas);
        ServiceTimeline row111 = new ServiceTimeline("ELT Battery Replacement",   false, "Replace",  "24 Months",  "06/10/2024", "06/10/2026", null, Tomas);
        ServiceTimeline row222 = new ServiceTimeline("Transponder Test",          false, "Test",     "24 Months",  "03/10/2025", "03/10/2027", null, Tomas);
        ServiceTimeline row333 = new ServiceTimeline("Magneto Inspection",        false, "Inspect",  "500 Hours",  "06/01/2024", "06/01/2025", null, Tomas);
        ServiceTimeline row444 = new ServiceTimeline("Pitot-Static System Test",  false, "Test",     "24 Months",  "03/10/2025", "03/10/2027", null, Tomas);

        // Daniel - 8 rows
        ServiceTimeline row11   = new ServiceTimeline("Annual Inspection",        false, "Inspect",  "12 Months",  "02/01/2026", "02/01/2027", null, Daniel);
        ServiceTimeline row22   = new ServiceTimeline("Oil Filter Replacement",   false, "Replace",  "50 Hours",   "11/05/2025", "12/15/2025", null, Daniel);
        ServiceTimeline row33   = new ServiceTimeline("Landing Gear Inspection",  false, "Inspect",  "12 Months",  "02/01/2026", "02/01/2027", null, Daniel);
        ServiceTimeline row44   = new ServiceTimeline("VOR Check",                false, "Test",     "30 Days",    "05/25/2026", "06/24/2026", null, Daniel);
        ServiceTimeline row1111 = new ServiceTimeline("Brake Lining Inspection",  false, "Inspect",  "100 Hours",  "12/01/2025", "02/10/2026", null, Daniel);
        ServiceTimeline row2222 = new ServiceTimeline("Propeller Inspection",     false, "Inspect",  "12 Months",  "02/01/2026", "02/01/2027", null, Daniel);
        ServiceTimeline row3333 = new ServiceTimeline("Fuel System Inspection",   false, "Inspect",  "12 Months",  "02/01/2026", "02/01/2027", null, Daniel);
        ServiceTimeline row4444 = new ServiceTimeline("Control Cable Inspection", false, "Inspect",  "12 Months",  "02/01/2026", "02/01/2027", null, Daniel);

        serviceTimelineRepository.saveAll(List.of(
            row1, row2, row3, row4, row111, row222, row333, row444,
            row11, row22, row33, row44, row1111, row2222, row3333, row4444
        ));
    }
}
