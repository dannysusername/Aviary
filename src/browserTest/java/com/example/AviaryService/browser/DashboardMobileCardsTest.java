package com.example.AviaryService.browser;

import com.example.AviaryService.entity.FlightLog;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.FlightLogRepository;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the mobile cards.
 *
 * At phone width (<=768px) each Service Timeline row and each Log Book row
 * collapses into a card. The current design requires, on a 390px viewport:
 *  - the card lays out as a CSS grid (not the old space-between flex rows),
 *  - the Item is a bold headline (font-weight 700),
 *  - the display fields are FLAT and BORDERLESS (no rounded "bubble" boxes) so
 *    they match the desktop cells,
 *  - Time Left is a plain readout (no green pill background),
 *  - the Complete action is a full-width button,
 *  - Log Book rows also lay out as a 2-column grid.
 *
 * The first test writes a full-page screenshot so a human can eyeball the
 * cards in the build report.
 */
class DashboardMobileCardsTest extends BrowserTestBase {

    private static final int PHONE_W = 390;
    private static final int PHONE_H = 844;

    @Autowired
    UserRepository users;

    @Autowired
    ServiceTimelineRepository timelines;

    @Autowired
    FlightLogRepository flightLogs;

    private void seedData(User user) {
        ServiceTimeline title = new ServiceTimeline();
        title.setUser(user);
        title.setItem("ENGINE");
        title.setIsTitle(true);
        title.setTimelineOrder(0);
        timelines.save(title);

        ServiceTimeline item = new ServiceTimeline();
        item.setUser(user);
        item.setItem("Annual Inspection");
        item.setIsTitle(false);
        item.setTimelineOrder(1);
        item.setDescription("Inspect");
        item.setCycleCalendarValue(12);
        item.setCycleCalendarUnit("MONTHS");
        item.setCycleHours(100.0);
        item.setLastDone("2025-06-01");
        item.setDueDate("2026-06-01 2450.0");
        item.setTimeLeft("11 mo\n86.0 hrs");
        timelines.save(item);

        FlightLog log = new FlightLog();
        log.setUser(user);
        log.setFromAirport("KSQL");
        log.setToAirport("KPAO");
        log.setHobbsOut(1234.5);
        log.setHobbsIn(1236.1);
        log.setTachOut(1100.2);
        log.setTachIn(1101.5);
        flightLogs.save(log);
    }

    private double pxOf(Locator loc, String prop) {
        Object v = loc.evaluate("(el, prop) => el.getBoundingClientRect()[prop]", prop);
        return ((Number) v).doubleValue();
    }

    private String cssOf(Locator loc, String prop) {
        return (String) loc.evaluate("(el, p) => getComputedStyle(el).getPropertyValue(p)", prop);
    }

    private void openDashboardOnPhone() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        seedData(u);
        page.setViewportSize(PHONE_W, PHONE_H);
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        // Last Done / Due Date inputs are injected by JS from the row's
        // data-* attributes on load — wait for one before measuring.
        page.locator("tr.auto-save-row td[data-label='Due Date'] input.extra-input")
                .first().waitFor();
    }

    @Test
    void serviceTimelineCardUsesFlatBorderlessFields() {
        openDashboardOnPhone();

        Locator row = page.locator("tr.auto-save-row").first();
        assertEquals("grid", cssOf(row, "display"),
                "Saved service-timeline card should lay out as a CSS grid on mobile");

        // Item is a bold headline.
        Locator itemText = row.locator("td[data-label='Item'] textarea");
        assertEquals("700", cssOf(itemText, "font-weight"),
                "Item should render as a bold headline (font-weight 700)");

        // Display fields are FLAT and BORDERLESS (no rounded "bubble"), matching
        // the desktop cells.
        assertEquals("none",
                cssOf(row.locator("td[data-label='Description'] .custom-dropdown"), "border-top-style"),
                "Description should be borderless on mobile (no bubble)");
        assertEquals("none",
                cssOf(row.locator("td[data-label='Cycle'] .cycle-structured"), "border-top-style"),
                "Cycle should be borderless on mobile (no bubble)");
        assertEquals("none",
                cssOf(row.locator("td[data-label='Due Date'] .input-with-dropdown"), "border-top-style"),
                "Due Date wrapper should be borderless on mobile (no bubble)");

        // Time Left is a plain readout — no green pill background.
        assertEquals("rgba(0, 0, 0, 0)",
                cssOf(row.locator(".time-left"), "background-color"),
                "Time Left should be a plain readout (transparent), not a pill");

        // The Complete action stays a comfortable full-width button.
        double completeH = pxOf(row.locator(".complete-btn"), "height");
        assertTrue(completeH >= 44, "Complete button should be >=44px tall, was " + completeH);
        double rowW = pxOf(row, "width");
        double completeW = pxOf(row.locator(".complete-btn"), "width");
        // grid padding is 14px each side; allow generous tolerance.
        assertTrue(completeW >= rowW - 60,
                "Complete button should span the card width (card " + rowW + ", btn " + completeW + ")");

        Path shot = Paths.get("build", "browsertest-screenshots", "mobile-cards-flat.png");
        try {
            Files.createDirectories(shot.getParent());
        } catch (Exception ignored) { }
        page.screenshot(new Page.ScreenshotOptions().setPath(shot).setFullPage(true));
    }

    @Test
    void logBookCardUsesTwoColumnGrid() {
        openDashboardOnPhone();

        // Switch to the Log Book tab and wait for it to become visible.
        page.locator("button.tab-button[data-tab='logbook-tab']").click();
        Locator logRow = page.locator("tr.log-row").first();
        logRow.waitFor();

        assertEquals("grid", cssOf(logRow, "display"),
                "Log Book card should lay out as a CSS grid on mobile");

        // Computed grid-template-columns is two pixel tracks, e.g. "171px 171px".
        String cols = cssOf(logRow, "grid-template-columns").trim();
        assertEquals(2, cols.split("\\s+").length,
                "Log Book card should have two columns, was: " + cols);

        double inputH = pxOf(logRow.locator("input[name='fromAirport']"), "height");
        assertTrue(inputH >= 44, "Log Book input should be >=44px tall, was " + inputH);

        Path shot = Paths.get("build", "browsertest-screenshots", "mobile-logbook-optionC.png");
        try {
            Files.createDirectories(shot.getParent());
        } catch (Exception ignored) { }
        page.screenshot(new Page.ScreenshotOptions().setPath(shot).setFullPage(true));
    }
}
