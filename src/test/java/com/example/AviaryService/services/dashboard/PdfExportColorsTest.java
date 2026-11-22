package com.example.AviaryService.services.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.StatusSheetItem;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.StatusSheetItemRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.services.alerts.LeadTimes;
import com.example.AviaryService.support.TestFleet;

// Spec 013 US3: the PDF marks due-soon items (not only overdue ones), using
// the owner's lead times. See contracts/pdf-time-left.md.
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PdfExportColorsTest {

    @Autowired PdfExportService pdfExportService;
    @Autowired UserRepository userRepository;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired StatusSheetItemRepository statusSheetItemRepository;

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 21);
    private Aircraft plane;

    @BeforeEach
    void setUp() {
        User u = new User();
        u.setUsername("pdf-colors-" + System.nanoTime());
        u.setPassword("x");
        userRepository.save(u);
        plane = TestFleet.addAircraft(aircraftRepository, userRepository, u, "N12SAS", "", 900, 680);
        item(u, "Annual", TODAY.minusDays(3).toString(), null, 0);          // overdue
        item(u, "ELT Battery", TODAY.plusDays(10).toString(), null, 1);     // due soon (30-day lead)
        item(u, "Fire Extinguisher", TODAY.plusDays(286).toString(), null, 2); // fine
        item(u, "Alternator Brushes", null, "688", 3);                    // 8 hrs left: due soon (10-hr lead)
    }

    private void item(User u, String name, String dueDate, String dueHours, int order) {
        StatusSheetItem t = new StatusSheetItem();
        t.setUser(u);
        t.setAircraft(plane);
        t.setItem(name);
        t.setIsTitle(false);
        t.setStatusSheetOrder(order);
        t.setDueDateDate(dueDate);
        t.setDueDateHours(dueHours);
        statusSheetItemRepository.save(t);
    }

    private String text(LeadTimes lead) throws Exception {
        byte[] pdf = pdfExportService.generateDashboardPdf(plane, TODAY, lead);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static int count(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    @Test
    void defaultLeadTimesMarkOverdueAndDueSoon() throws Exception {
        String t = text(LeadTimes.DEFAULT);
        assertEquals(1, count(t, "OVERDUE"), t);
        assertEquals(2, count(t, "DUE SOON"), t);
    }

    @Test
    void shorterDayLeadTimeLeavesOnlyTheHoursItemDueSoon() throws Exception {
        String t = text(new LeadTimes(7, 10));
        assertEquals(1, count(t, "OVERDUE"), t);
        assertEquals(1, count(t, "DUE SOON"), t);
    }
}
