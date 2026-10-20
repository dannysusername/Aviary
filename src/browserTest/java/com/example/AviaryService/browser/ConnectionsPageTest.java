package com.example.AviaryService.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.AviaryService.entity.OAuthAuthorizationEntity;
import com.example.AviaryService.entity.TimelineImportRecord;
import com.example.AviaryService.repositories.OAuthAuthorizationRepository;
import com.example.AviaryService.repositories.TimelineImportRecordRepository;

/**
 * Import with AI page: the connect box (address, Copy, steps, hints) is always
 * shown; a connected assistant is listed with its imports, and Disconnect
 * removes it -- at desktop and phone widths.
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

        assertThat(page.locator("#connect-box")).isVisible();
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
        assertThat(page.locator("body")).containsText("No assistants connected yet");
        assertThat(page.locator("#connect-box")).isVisible();
    }

    private void assertNoSidewaysScroll(int width) {
        Object overflow = page.evaluate("document.documentElement.scrollWidth > window.innerWidth");
        if (Boolean.TRUE.equals(overflow)) {
            throw new AssertionError("Import with AI page scrolls horizontally at " + width + "px");
        }
    }

    @Test
    void connectBoxShowsWithNoConnections() {
        page.setViewportSize(390, 844);
        String username = seedUser("password123");
        loginAs(username, "password123");
        page.navigate(baseUrl() + "/account/connections");

        assertThat(page.locator("h1")).hasText("Import with AI");
        assertThat(page.locator("#connect-box")).isVisible();
        String address = page.locator("#mcp-url").inputValue();
        if (!address.endsWith("/mcp")) throw new AssertionError("Unexpected address: " + address);
        assertThat(page.locator("#connect-steps li")).hasCount(3);
        if (page.locator("#assistant-hints li").count() < 1) throw new AssertionError("No assistant hints");
        assertThat(page.locator("#connect-next")).containsText("preview");
        assertThat(page.locator("body")).containsText("No assistants connected yet");
        assertNoSidewaysScroll(390);
    }

    @Test
    void copyButtonCopiesTheAddress() {
        context.grantPermissions(List.of("clipboard-read", "clipboard-write"), new com.microsoft.playwright.BrowserContext.GrantPermissionsOptions().setOrigin(baseUrl()));
        String username = seedUser("password123");
        loginAs(username, "password123");
        page.navigate(baseUrl() + "/account/connections");

        String address = page.locator("#mcp-url").inputValue();
        page.locator("#copy-mcp-url").click();
        assertThat(page.locator("#copy-mcp-url")).hasText("Copied");
        Object clipboard = page.evaluate("navigator.clipboard.readText()");
        if (!address.equals(clipboard)) {
            throw new AssertionError("Clipboard has '" + clipboard + "', expected '" + address + "'");
        }
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
