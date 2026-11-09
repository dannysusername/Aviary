package com.example.AviaryService.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.LoadState;

/**
 * FR-005a / SC-001a: adding an aircraft on a phone (375x667) takes 3 taps plus
 * typing the tail number, the form fits the screen, the tail field gets a
 * capitals keyboard without zoom-on-focus, Save stays visible with the
 * keyboard open, errors keep what was typed, and the new aircraft's first
 * step is on screen. See specs/003-multi-aircraft-fleet/contracts/ui.md.
 */
class MobileAddAircraftTest extends BrowserTestBase {

    private static final int PHONE_W = 375;
    private static final int PHONE_H = 667;

    private boolean inViewport(Locator loc) {
        return (Boolean) loc.evaluate("el => { const r = el.getBoundingClientRect();"
            + " return r.top >= 0 && r.left >= 0 && r.bottom <= window.innerHeight && r.right <= window.innerWidth; }");
    }

    private void assertNoSidewaysScroll() {
        Object w = page.evaluate("() => document.documentElement.scrollWidth");
        assertTrue(((Number) w).intValue() <= PHONE_W, "page scrolls sideways: scrollWidth=" + w);
    }

    private String cssOf(Locator loc, String prop) {
        return (String) loc.evaluate("(el, p) => getComputedStyle(el).getPropertyValue(p)", prop);
    }

    @Test
    void addAircraftOnAPhoneInThreeTaps() {
        String username = seedUser("password");
        page.setViewportSize(PHONE_W, PHONE_H);
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        assertNoSidewaysScroll();
        page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions()
            .setPath(java.nio.file.Paths.get("build/screenshots/mobile-header-375.png")));

        // Tap 1: the aircraft button in the header.
        Locator pickerBtn = page.locator("#aircraft-picker-btn");
        assertTrue(pickerBtn.boundingBox().height >= 44 && pickerBtn.boundingBox().width >= 44, "picker button too small");
        pickerBtn.click();
        assertThat(page.locator("#aircraft-panel")).isVisible();
        assertNoSidewaysScroll();

        // Tap 2: Add aircraft.
        Locator addBtn = page.locator("#add-aircraft-btn");
        assertTrue(addBtn.boundingBox().height >= 44, "Add aircraft too small");
        addBtn.click();

        Locator tail = page.locator("#new-tail-number");
        assertThat(tail).isFocused();
        assertEquals("characters", tail.getAttribute("autocapitalize"));
        assertEquals("off", tail.getAttribute("autocorrect"));
        assertTrue(Double.parseDouble(cssOf(tail, "font-size").replace("px", "")) >= 16, "tail input under 16px zooms on iOS");
        assertNoSidewaysScroll();

        tail.fill("n 731 kr");
        page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions()
            .setPath(java.nio.file.Paths.get("build/screenshots/mobile-add-aircraft-375.png")));

        // On-screen keyboard: the visible height shrinks. Save must stay visible.
        page.setViewportSize(PHONE_W, 400);
        Locator save = page.locator("#add-aircraft-save");
        page.waitForTimeout(150);
        assertTrue(inViewport(save), "Save is hidden behind the keyboard");
        page.setViewportSize(PHONE_W, PHONE_H);

        // Tap 3: Save -> lands on the new aircraft.
        save.click();
        page.waitForURL(Pattern.compile(".*/dashboard\\?aircraft=\\d+"));
        page.waitForLoadState(LoadState.NETWORKIDLE);

        assertThat(page.locator(".aircraft-picker-name")).hasText("N731KR");
        Locator firstStep = page.locator("#add-first-item-btn");
        assertThat(firstStep).isVisible();
        assertTrue(inViewport(firstStep), "first maintenance item button needs scrolling");
        page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions()
            .setPath(java.nio.file.Paths.get("build/screenshots/mobile-new-aircraft-375.png")));
        assertNoSidewaysScroll();
    }

    @Test
    void duplicateTailShowsInlineErrorAndKeepsText() {
        String username = seedUser("password");
        var a = aircraftOf(username);
        a.setTailNumber("N482DM");
        aircraftRepository.save(a);

        page.setViewportSize(PHONE_W, PHONE_H);
        loginAs(username, "password");
        page.locator("#aircraft-picker-btn").click();
        page.locator("#add-aircraft-btn").click();
        page.locator("#new-tail-number").fill("n482dm");
        page.locator("#add-aircraft-save").click();

        assertThat(page.locator("#new-tail-number-error")).hasText("You already have an aircraft with this tail number.");
        assertThat(page.locator("#new-tail-number")).hasValue("n482dm");
        assertThat(page.locator("#new-tail-number")).hasAttribute("aria-invalid", "true");
        assertTrue(page.url().endsWith("/dashboard"), page.url());
    }
}
