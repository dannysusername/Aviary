package com.example.AviaryService.browser;

import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Smoke test: proves the whole browser-test rig works end-to-end before
 * we write real edge-case tests. Seeds a user, logs in via the real
 * /login form, asserts the dashboard renders without console errors.
 *
 * If this passes, the infra is good and we can layer table-specific
 * edge-case tests on top.
 */
class LoginSmokeTest extends BrowserTestBase {

    @Test
    void seededUserCanLogInAndSeeDashboard() {
        String username = seedUser("password123");

        // Capture browser console errors during the whole flow — silent JS
        // errors are exactly the kind of bug Playwright is supposed to
        // catch and pure backend tests miss.
        List<String> consoleErrors = new ArrayList<>();
        page.onConsoleMessage(msg -> {
            if ("error".equals(msg.type())) {
                consoleErrors.add(msg.text());
            }
        });

        loginAs(username, "password123");

        // Wait for the dashboard JS to settle (Thymeleaf renders synchronously
        // but dashboard.js may fetch/transform on load).
        page.waitForLoadState(LoadState.NETWORKIDLE);

        // Sanity: the page is the dashboard, not the login page after a
        // failed redirect.
        assertThat(page).hasURL(baseUrl() + "/dashboard");
        assertThat(page.locator("body")).isVisible();

        // No console errors during render.
        if (!consoleErrors.isEmpty()) {
            throw new AssertionError("Console errors during dashboard render: " + consoleErrors);
        }
    }
}
