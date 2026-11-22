package com.example.AviaryService.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.AviaryService.repositories.AlertPreferenceRepository;
import com.microsoft.playwright.Browser;

// Spec 014 matrix E: the alert schedule settings -- three emails with clear
// defaults and one-line explanations, saving, and the owner's time zone.
class AlertSettingsTest extends BrowserTestBase {

    @Autowired AlertPreferenceRepository preferenceRepository;

    private String openSettings() {
        String username = seedUser("password");
        loginAs(username, "password");
        page.navigate(baseUrl() + "/dashboard");
        page.locator("#settings").click();
        assertThat(page.locator("#alerts-due-soon-every")).isVisible();
        return username;
    }

    @Test
    void e1_defaultsAndExplanations() {
        openSettings();
        assertThat(page.locator("#alerts-due-soon-every")).hasValue("7");
        assertThat(page.locator("#alerts-overdue-every")).hasValue("3");
        assertThat(page.locator("#alerts-status-every")).hasValue("off");
        assertThat(page.locator("#alerts-due-soon-hint")).hasText("Email me every 7 days while anything is due soon.");
        assertThat(page.locator("#alerts-overdue-hint")).hasText("Email me every 3 days while anything is overdue.");
        assertThat(page.locator("#alerts-status-hint")).hasText("No scheduled status email.");
        // Status report has no "Once".
        assertEquals(0, page.locator("#alerts-status-every option[value='once']").count());
    }

    @Test
    void e2_changesAreSaved() {
        openSettings();
        page.locator("#alerts-due-soon-every").selectOption("off");
        page.locator("#alerts-overdue-every").selectOption("2");
        page.locator("#alerts-status-every").selectOption("custom");
        page.locator("#alerts-status-every-days").fill("10");
        assertThat(page.locator("#alerts-status-hint")).hasText("Email the full status every 10 days, even when nothing is due.");
        page.waitForResponse(r -> r.url().endsWith("/alerts/preferences") && "PUT".equals(r.request().method()),
            () -> page.locator("#alerts-save-schedule").click());

        page.reload();
        page.locator("#settings").click();
        assertThat(page.locator("#alerts-due-soon-every")).hasValue("off");
        assertThat(page.locator("#alerts-due-soon-hint")).hasText("Never email just because something is due soon.");
        assertThat(page.locator("#alerts-overdue-every")).hasValue("2");
        assertThat(page.locator("#alerts-status-every")).hasValue("custom");
        assertThat(page.locator("#alerts-status-every-days")).hasValue("10");
    }

    @Test
    void e3_timeZoneComesFromTheBrowser() {
        context.close();
        context = browser.newContext(new Browser.NewContextOptions().setTimezoneId("America/Chicago"));
        page = context.newPage();
        String username = openSettings();
        assertThat(page.locator("#alerts-time-zone")).hasText("America/Chicago");
        page.waitForTimeout(500); // the POST that saves it
        assertEquals("America/Chicago", preferenceRepository.findByUser(userRepository.findByUsername(username))
            .orElseThrow().getTimeZone());
    }
}
