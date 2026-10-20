package com.example.AviaryService.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.AviaryService.entity.OAuthAuthorizationEntity;
import com.example.AviaryService.entity.TimelineImportRecord;
import com.example.AviaryService.repositories.OAuthAuthorizationRepository;
import com.example.AviaryService.repositories.TimelineImportRecordRepository;

/**
 * Account -> Connected assistants: a connected assistant is listed, the
 * dashboard links to the page, and Disconnect removes it -- at desktop and
 * phone widths.
 */
class ConnectionsPageTest extends BrowserTestBase {

    @Autowired
    OAuthAuthorizationRepository authorizationRepository;

    @Autowired
    TimelineImportRecordRepository importRecordRepository;

    private void seedConnection(String username) {
        OAuthAuthorizationEntity e = new OAuthAuthorizationEntity();
        e.setId(UUID.randomUUID().toString());
        e.setRegisteredClientId("https://assistant.example.com/oauth/client.json");
        e.setPrincipalName(username);
        e.setAuthorizationGrantType("authorization_code");
        e.setClientName("Test Assistant");
        e.setAccessTokenValue("sha256:" + UUID.randomUUID());
        e.setAccessTokenIssuedAt(Instant.now());
        authorizationRepository.save(e);

        TimelineImportRecord r = new TimelineImportRecord();
        r.setUser(userRepository.findByUsername(username));
        r.setClientId(e.getRegisteredClientId());
        r.setClientName("Test Assistant");
        r.setItemCount(3);
        r.setImportedAt(Instant.now());
        importRecordRepository.save(r);
    }

    private void listAndDisconnect(int width, int height) {
        page.setViewportSize(width, height);
        String username = seedUser("password123");
        seedConnection(username);
        loginAs(username, "password123");

        page.locator("#connections-link").click();
        page.waitForURL(baseUrl() + "/account/connections");

        assertThat(page.locator(".connection-name")).hasText("Test Assistant");
        assertThat(page.locator(".connection-meta")).containsText("assistant.example.com");
        assertThat(page.locator(".connection-imports")).containsText("added 3 items");

        // No sideways scrolling on a phone.
        Object overflow = page.evaluate("document.documentElement.scrollWidth > window.innerWidth");
        if (Boolean.TRUE.equals(overflow)) {
            throw new AssertionError("Connections page scrolls horizontally at " + width + "px");
        }

        page.locator("button.connection-disconnect").click();
        page.waitForURL("**/account/connections?disconnected");
        assertThat(page.locator(".connection")).hasCount(0);
        assertThat(page.locator("body")).containsText("No AI assistants are connected");
    }

    @Test
    void desktop() {
        listAndDisconnect(1280, 800);
    }

    @Test
    void phone() {
        listAndDisconnect(390, 844);
    }
}
