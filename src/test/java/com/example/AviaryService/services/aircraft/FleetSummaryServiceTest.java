package com.example.AviaryService.services.aircraft;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.AlertPreference;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.services.alerts.AlertService;
import com.example.AviaryService.support.TestFleet;

// US4: overdue / due-soon counts per aircraft, each row judged against its
// own aircraft's time in service, with the same lead times as alerts.
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FleetSummaryServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

    @Autowired FleetSummaryService summaryService;
    @Autowired AlertService alertService;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired ServiceTimelineRepository timelineRepository;
    @Autowired UserRepository userRepository;

    private User owner;
    private Aircraft low;   // 100 hrs
    private Aircraft high;  // 1000 hrs

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setUsername("summary-" + System.nanoTime());
        owner.setPassword("x");
        userRepository.save(owner);
        low = TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N1LO", "", 0, 100);
        high = TestFleet.addAircraft(aircraftRepository, userRepository, owner, "N2HI", "", 0, 1000);
    }

    private void row(Aircraft a, String dueDate, String dueHours, boolean title) {
        ServiceTimeline t = new ServiceTimeline();
        t.setUser(owner);
        t.setAircraft(a);
        t.setItem("x");
        t.setIsTitle(title);
        t.setTimelineOrder(0);
        t.setDueDateDate(dueDate);
        t.setDueDateHours(dueHours);
        timelineRepository.save(t);
    }

    @Test
    void hoursRowsUseTheirOwnAircraftsHours() {
        // Due at 500 hrs: fine for the 100-hr plane, overdue for the 1000-hr one.
        row(low, null, "500", false);
        row(high, null, "500", false);

        Map<Long, FleetSummaryService.Counts> c = summaryService.summarize(owner, TODAY);
        assertEquals(new FleetSummaryService.Counts(0, 0), c.get(low.getId()));
        assertEquals(new FleetSummaryService.Counts(1, 0), c.get(high.getId()));
    }

    @Test
    void calendarRowsAndTitlesAndDefaults() {
        row(low, "2026-11-01", null, false);  // overdue
        row(low, "2026-11-30", null, false);  // 21 days -> due soon (default 30)
        row(low, "2027-06-01", null, false);  // ok
        row(low, "2020-01-01", null, true);   // title: ignored
        row(high, null, "1005", false);       // 5 hrs left -> due soon (default 10)

        Map<Long, FleetSummaryService.Counts> c = summaryService.summarize(owner, TODAY);
        assertEquals(new FleetSummaryService.Counts(1, 1), c.get(low.getId()));
        assertEquals(new FleetSummaryService.Counts(0, 1), c.get(high.getId()));
    }

    @Test
    void usesTheAccountsAlertLeadTimes() {
        AlertPreference prefs = alertService.getOrCreatePrefs(owner);
        prefs.setLeadTimeDays(7);
        row(low, "2026-11-30", null, false); // 21 days: not due soon with a 7-day lead

        assertEquals(new FleetSummaryService.Counts(0, 0), summaryService.summarize(owner, TODAY).get(low.getId()));
    }
}
