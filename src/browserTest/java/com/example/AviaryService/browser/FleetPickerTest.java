package com.example.AviaryService.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;

/**
 * US3 / US4: switching aircraft, the remembered choice, per-aircraft tables,
 * due counts in the picker, and the two-tab rule (an action lands on the
 * aircraft on screen, not whichever was picked last).
 */
class FleetPickerTest extends BrowserTestBase {

    @Autowired ServiceTimelineRepository timelines;

    private Aircraft a;
    private Aircraft b;
    private String username;

    private void seedFleet() {
        username = seedUser("password");
        User u = userRepository.findByUsername(username);
        a = aircraftOf(u);
        a.setTailNumber("N1AA");
        a.setMakeModel("Cessna 172");
        a = aircraftRepository.save(a);
        b = new Aircraft(u);
        b.setTailNumber("N2BB");
        b.setMakeModel("Piper PA-28");
        b = aircraftRepository.save(b);
        row(u, a, "Alpha Annual", 0, "2099-01-01");
        row(u, a, "Alpha Oil", 1, "2099-01-01");
        row(u, b, "Bravo ELT", 0, "2020-01-01"); // overdue
    }

    private void row(User u, Aircraft on, String item, int order, String due) {
        ServiceTimeline t = new ServiceTimeline();
        t.setUser(u);
        t.setAircraft(on);
        t.setItem(item);
        t.setIsTitle(false);
        t.setTimelineOrder(order);
        t.setDueDateDate(due);
        timelines.save(t);
    }

    private List<String> visibleItems(Page p) {
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) p.locator("tr.auto-save-row td[data-label='Item'] textarea")
            .evaluateAll("els => els.map(e => e.value)");
        return values;
    }

    @Test
    void switchShowsThatAircraftAndIsRemembered() {
        seedFleet();
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        assertThat(page.locator(".aircraft-picker-name")).hasText("N1AA");
        assertEquals(List.of("Alpha Annual", "Alpha Oil"), visibleItems(page));

        page.locator("#aircraft-picker-btn").click();
        assertThat(page.locator(".aircraft-option")).hasCount(2);
        assertThat(page.locator(".aircraft-option[aria-current='true'] .aircraft-option-name")).hasText("N1AA");
        assertThat(page.locator(".aircraft-option").nth(1)).containsText("Piper PA-28");

        page.locator(".aircraft-option").nth(1).click();
        page.waitForURL(baseUrl() + "/dashboard?aircraft=" + b.getId());
        assertThat(page.locator(".aircraft-picker-name")).hasText("N2BB");
        assertEquals(List.of("Bravo ELT"), visibleItems(page));

        page.reload();
        assertThat(page.locator(".aircraft-picker-name")).hasText("N2BB");

        // A fresh login lands on the last aircraft viewed.
        context.clearCookies();
        loginAs(username, "password");
        assertThat(page.locator(".aircraft-picker-name")).hasText("N2BB");
    }

    @Test
    void pickerShowsDueCountsAndFlagsOtherAircraft() {
        seedFleet();
        loginAs(username, "password");
        // Viewing A while B has an overdue item: the button shows a dot.
        assertThat(page.locator("#aircraft-picker-btn .aircraft-overdue-dot")).isVisible();
        page.locator("#aircraft-picker-btn").click();
        // Nothing due: no chip at all.
        assertThat(page.locator(".aircraft-option").nth(0).locator(".chip")).hasCount(0);
        assertThat(page.locator(".aircraft-option").nth(1).locator(".chip-overdue")).hasText("1 overdue");
    }

    @Test
    void actionInAnOlderTabLandsOnThatTabsAircraft() {
        seedFleet();
        loginAs(username, "password"); // tab 1 shows A
        Page tab2 = context.newPage();
        tab2.navigate(baseUrl() + "/dashboard?aircraft=" + b.getId()); // now B is "last viewed"
        tab2.waitForLoadState(LoadState.NETWORKIDLE);

        Object status = page.evaluate("async () => {"
            + " const t = document.querySelector('meta[name=\"_csrf\"]').content;"
            + " const h = document.querySelector('meta[name=\"_csrf_header\"]').content;"
            + " const r = await axios.post('/dashboard', { item: 'Tab one row', ajax: 'true' }, { headers: { [h]: t } });"
            + " return r.status; }");
        assertEquals(200, ((Number) status).intValue());

        assertTrue(timelines.findByAircraftOrderByTimelineOrderAsc(a).stream().anyMatch(t -> t.getItem().equals("Tab one row")));
        assertTrue(timelines.findByAircraftOrderByTimelineOrderAsc(b).stream().noneMatch(t -> t.getItem().equals("Tab one row")));
    }

    // The plane button must never sit under Share / Import with AI / Logout,
    // and the info card fields stay in one column.
    @Test
    void headerButtonsNeverOverlapThePlaneButton() {
        seedFleet();
        a.setTailNumber("N1234567AB");
        aircraftRepository.save(a);
        loginAs(username, "password");
        for (int width : new int[] {375, 480, 640, 760, 900, 1280}) {
            page.setViewportSize(width, 800);
            page.waitForTimeout(100);
            var plane = page.locator("#aircraft-picker-btn").boundingBox();
            var right = page.locator(".fixed-header .header-right").boundingBox();
            assertTrue(plane.x + plane.width <= right.x + 0.5,
                "plane button overlaps the header buttons at " + width + "px");
            assertTrue(plane.width >= 44, "plane button squeezed below 44px at " + width + "px");
            Object scroll = page.evaluate("() => document.documentElement.scrollWidth > window.innerWidth");
            assertEquals(Boolean.FALSE, scroll, "sideways scroll at " + width + "px");
        }
        page.setViewportSize(1280, 800);
        @SuppressWarnings("unchecked")
        List<Number> lefts = (List<Number>) page.locator(".aircraft-info-table input.user-info-input")
            .evaluateAll("els => els.map(e => Math.round(e.getBoundingClientRect().left))");
        assertEquals(1, lefts.stream().distinct().count(), "info card fields not aligned: " + lefts);
    }

    @Test
    void singleAircraftDashboardIsUnchangedApartFromAdd() {
        username = seedUser("password");
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        assertThat(page.locator(".aircraft-picker-name")).hasText("Unnamed aircraft");
        assertThat(page.locator("#remove-aircraft-btn")).hasCount(0); // no "Remove aircraft"
        page.locator("#aircraft-picker-btn").click();
        assertThat(page.locator(".aircraft-option")).hasCount(1);
        assertThat(page.locator("#add-aircraft-btn")).isEnabled();
    }

    @Test
    void removeAircraftNeedsTheTailNumber() {
        seedFleet();
        loginAs(username, "password");
        page.navigate(baseUrl() + "/dashboard?aircraft=" + b.getId());
        page.locator("#aircraft-picker-btn").click();
        assertThat(page.locator("#remove-aircraft-btn")).hasText("Remove N2BB…");
        page.locator("#remove-aircraft-btn").click();
        assertThat(page.locator("#remove-aircraft-dialog")).isVisible();
        assertThat(page.locator("#remove-aircraft-ok")).isDisabled();
        page.locator("#remove-aircraft-confirm").fill("n2 bb");
        assertThat(page.locator("#remove-aircraft-ok")).isEnabled();
        page.locator("#remove-aircraft-ok").click();
        page.waitForURL(baseUrl() + "/dashboard?aircraft=" + a.getId());
        assertTrue(aircraftRepository.findById(b.getId()).isEmpty());
        assertEquals(List.of("Alpha Annual", "Alpha Oil"), visibleItems(page));
    }
}
