package com.example.AviaryService.browser;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 005: info marks next to Hobbs Time and Time in Service on My Hours.
 * Checks the text, that hover/focus/tap reveal it, that tapping elsewhere
 * closes it, and that it fits a phone screen without horizontal scroll.
 */
class HoursInfoMarksTest extends BrowserTestBase {

    private static final String HOBBS_TIP =
        "Hobbs time: engine running time, from engine start to shutdown. Includes taxi and run-up.";
    private static final String TIS_TIP =
        "Flight time: time in the air, from takeoff to landing. Used for maintenance and inspections.";

    private String tipDisplay(Locator mark) {
        return (String) mark.evaluate("el => getComputedStyle(el, '::after').display");
    }

    private List<String> openDashboard() {
        String username = seedUser("password");
        List<String> errors = new ArrayList<>();
        page.onConsoleMessage(m -> { if ("error".equals(m.type())) errors.add(m.text()); });
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        return errors;
    }

    @Test
    void infoMarksHaveTheRightTextAndOpenOnHoverFocusAndTap() {
        List<String> errors = openDashboard();
        Locator hobbs = page.locator("#hobbs-time-info");
        Locator tis = page.locator("#time-in-service-info");

        assertThat(hobbs).hasAttribute("aria-label", HOBBS_TIP);
        assertThat(hobbs).hasAttribute("data-tip", HOBBS_TIP);
        assertThat(tis).hasAttribute("aria-label", TIS_TIP);
        assertThat(tis).hasAttribute("data-tip", TIS_TIP);

        assertEquals("none", tipDisplay(hobbs), "tooltip hidden by default");

        hobbs.hover();
        page.waitForTimeout(200);
        assertEquals("block", tipDisplay(hobbs), "hover shows tooltip");

        page.mouse().move(0, 0);
        tis.click();
        page.waitForTimeout(200);
        assertTrue(tis.getAttribute("class").contains("open"), "tap opens tooltip");
        page.locator("h3:has-text('My Hours')").click();
        assertTrue(!tis.getAttribute("class").contains("open"), "tap elsewhere closes it");

        page.keyboard().press("Tab");
        tis.focus();
        page.keyboard().press("Escape");
        assertTrue(!tis.getAttribute("class").contains("open"));

        if (!errors.isEmpty()) throw new AssertionError("Console errors: " + errors);
    }

    @Test
    void statusSheetTabHasMaintenanceInspectionsTag() {
        List<String> errors = openDashboard();
        Locator tag = page.locator("#status-sheet-info");
        assertThat(page.locator(".tab-button[data-tab='status-sheet-tab']")).hasText("Status Sheet");
        assertThat(tag).hasAttribute("data-tip", "Maintenance/inspections");
        assertThat(tag).hasAttribute("aria-label", "Status Sheet: maintenance/inspections");
        tag.hover();
        page.waitForTimeout(200);
        assertEquals("block", tipDisplay(tag), "hover shows the tag text");
        if (!errors.isEmpty()) throw new AssertionError("Console errors: " + errors);
    }

    @Test
    void infoMarksFitAPhoneScreen() {
        page.setViewportSize(360, 740);
        openDashboard();
        Locator tis = page.locator("#time-in-service-info");
        tis.scrollIntoViewIfNeeded();
        tis.click();
        page.waitForTimeout(200);
        Object overflow = page.evaluate(
            "() => document.documentElement.scrollWidth > document.documentElement.clientWidth");
        assertEquals(false, overflow, "open tooltip must not cause horizontal scroll at 360px");
    }
}
