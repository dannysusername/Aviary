package com.example.AviaryService.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.microsoft.playwright.options.LoadState;

/**
 * Ways into the Import with AI page from the dashboard: the labeled header
 * control (text on desktop, named icon on a phone) and the prompt shown only
 * while the service timeline is empty.
 */
class ImportWithAiEntryTest extends BrowserTestBase {

    @Autowired
    ServiceTimelineRepository timelineRepository;

    private void assertNoSidewaysScroll(int width) {
        Object overflow = page.evaluate("document.documentElement.scrollWidth > window.innerWidth");
        if (Boolean.TRUE.equals(overflow)) {
            throw new AssertionError("Dashboard scrolls horizontally at " + width + "px");
        }
    }

    // ---- header control (US2) ----

    @Test
    void headerShowsLabeledControlOnDesktop() {
        page.setViewportSize(1280, 800);
        loginAs(seedUser("password123"), "password123");

        assertThat(page.locator("#connections-link")).containsText("Import with AI");
        assertThat(page.locator("#connections-link .connections-link-text")).isVisible();
        page.locator("#connections-link").click();
        page.waitForURL(baseUrl() + "/account/connections");
    }

    @Test
    void headerShowsNamedIconOnPhone() {
        page.setViewportSize(390, 844);
        loginAs(seedUser("password123"), "password123");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        assertThat(page.locator("#connections-link")).isVisible();
        assertThat(page.locator("#connections-link")).hasAttribute("aria-label", "Import with AI");
        assertNoSidewaysScroll(390);
    }

    // ---- empty-timeline prompt (US3) ----

    @Test
    void emptyTimelineShowsPrompt() {
        page.setViewportSize(1280, 800);
        loginAs(seedUser("password123"), "password123");

        assertThat(page.locator("#import-ai-prompt")).isVisible();
        page.locator("#import-ai-prompt a").click();
        page.waitForURL(baseUrl() + "/account/connections");
    }

    @Test
    void neitherControlPrints() {
        page.setViewportSize(1280, 800);
        loginAs(seedUser("password123"), "password123");
        page.emulateMedia(new com.microsoft.playwright.Page.EmulateMediaOptions()
            .setMedia(com.microsoft.playwright.options.Media.PRINT));
        assertThat(page.locator("#import-ai-prompt")).isHidden();
        assertThat(page.locator("#connections-link")).isHidden();
    }

    @Test
    void timelineWithItemsHasNoPrompt() {
        page.setViewportSize(1280, 800);
        String username = seedUser("password123");
        ServiceTimeline t = new ServiceTimeline();
        t.setUser(userRepository.findByUsername(username));
        t.setItem("Annual");
        t.setTimelineOrder(0);
        timelineRepository.save(t);
        loginAs(username, "password123");

        assertThat(page.locator("#import-ai-prompt")).hasCount(0);
    }

    @Test
    void addingFirstRowRemovesPromptWithoutReload() {
        page.setViewportSize(1280, 800);
        loginAs(seedUser("password123"), "password123");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        assertThat(page.locator("#import-ai-prompt")).isVisible();

        page.locator(".add-row td[data-label='Item'] textarea").fill("Oil change");
        page.locator("td.add-cell form button.add-button").click();

        assertThat(page.locator(".sortable tr[data-id]")).hasCount(1);
        assertThat(page.locator("#import-ai-prompt")).hasCount(0);
    }
}
