package com.example.AviaryService.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.AviaryService.entity.StatusSheetItem;
import com.example.AviaryService.repositories.StatusSheetItemRepository;
import com.microsoft.playwright.options.LoadState;

/**
 * Ways into the Import with AI page from the dashboard: the labeled header
 * control (text on desktop, named icon on a phone). The empty Status Sheet
 * "Have records in a spreadsheet?" prompt was removed; an empty aircraft
 * shows only "Add your first maintenance item".
 */
class ImportWithAiEntryTest extends BrowserTestBase {

    @Autowired
    StatusSheetItemRepository statusSheetItemRepository;

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

    // ---- empty Status Sheet ----

    @Test
    void emptyStatusSheetShowsFirstItemButtonAndNoImportPrompt() {
        page.setViewportSize(1280, 800);
        loginAs(seedUser("password123"), "password123");

        assertThat(page.locator("#add-first-item-btn")).isVisible();
        assertThat(page.locator("#import-ai-prompt")).hasCount(0);
    }

    @Test
    void neitherControlPrints() {
        page.setViewportSize(1280, 800);
        loginAs(seedUser("password123"), "password123");
        page.emulateMedia(new com.microsoft.playwright.Page.EmulateMediaOptions()
            .setMedia(com.microsoft.playwright.options.Media.PRINT));
        assertThat(page.locator("#empty-statusSheetItem")).isHidden();
        assertThat(page.locator("#connections-link")).isHidden();
    }

    @Test
    void statusSheetWithItemsHasNoPrompt() {
        page.setViewportSize(1280, 800);
        String username = seedUser("password123");
        StatusSheetItem t = new StatusSheetItem();
        t.setUser(userRepository.findByUsername(username));
        t.setAircraft(aircraftOf(userRepository.findByUsername(username)));
        t.setItem("Annual");
        t.setStatusSheetOrder(0);
        statusSheetItemRepository.save(t);
        loginAs(username, "password123");

        assertThat(page.locator("#empty-statusSheetItem")).hasCount(0);
    }

    @Test
    void addingFirstRowRemovesPromptWithoutReload() {
        page.setViewportSize(1280, 800);
        loginAs(seedUser("password123"), "password123");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        assertThat(page.locator("#empty-statusSheetItem")).isVisible();

        page.locator(".add-row td[data-label='Item'] textarea").fill("Oil change");
        page.locator("td.add-cell form button.add-button").click();

        assertThat(page.locator(".sortable tr[data-id]")).hasCount(1);
        assertThat(page.locator("#empty-statusSheetItem")).hasCount(0);
    }
}
