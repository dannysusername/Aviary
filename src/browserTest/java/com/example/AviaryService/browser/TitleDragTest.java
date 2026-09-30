package com.example.AviaryService.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.LoadState;

/**
 * Section titles can be dragged by their grip like item rows, on desktop and
 * on the mobile card layout (where the title grip used to be hidden), and the
 * new order is saved.
 */
class TitleDragTest extends BrowserTestBase {

    @Autowired
    ServiceTimelineRepository timelines;

    private User seed() {
        String username = seedUser("password");
        User u = userRepository.findByUsername(username);
        save(u, "Section A", true, 0);
        save(u, "Alpha Item", false, 1);
        save(u, "Section B", true, 2);
        return u;
    }

    private void save(User u, String item, boolean isTitle, int order) {
        ServiceTimeline t = new ServiceTimeline();
        t.setUser(u);
        t.setItem(item);
        t.setIsTitle(isTitle);
        t.setTimelineOrder(order);
        timelines.save(t);
    }

    private List<String> domOrder() {
        return page.locator("tbody.sortable > tr:not(.add-row)").all().stream()
            .map(r -> r.getAttribute("class") != null && r.getAttribute("class").contains("title-row")
                ? r.locator("td.title-cell").innerText().trim()
                : r.locator("textarea[name='item']").inputValue())
            .collect(Collectors.toList());
    }

    private void dragSectionBAboveSectionA() {
        Locator titles = page.locator("tr.title-row");
        page.evaluate("() => { const r = document.querySelector('tr.title-row').getBoundingClientRect();"
            + " window.scrollBy(0, r.top - 80); }");
        BoundingBox grip = titles.nth(1).locator(".grip-icon").boundingBox();
        BoundingBox target = titles.nth(0).boundingBox();
        assertTrue(grip != null && grip.width > 0, "Section B's grip should be visible");

        double sx = grip.x + grip.width / 2, sy = grip.y + grip.height / 2;
        double ex = target.x + target.width / 2, ey = target.y + target.height * 0.25;
        page.mouse().move(sx, sy);
        page.mouse().down();
        page.mouse().move(sx, sy - 12, new Mouse.MoveOptions().setSteps(4));
        page.mouse().move(ex, ey, new Mouse.MoveOptions().setSteps(25));
        page.mouse().move(ex, ey - 4, new Mouse.MoveOptions().setSteps(4));
        page.mouse().up();
        page.waitForTimeout(500); // onEnd -> POST /updateOrder
    }

    private void assertSectionBFirstAndSaved(User u) {
        assertEquals("Section B", domOrder().get(0), "Section B should be first after the drag, order was " + domOrder());
        List<ServiceTimeline> saved = timelines.findByUserOrderByTimelineOrderAsc(u);
        assertEquals("Section B", saved.get(0).getItem(), "New order should be saved to the server");
    }

    @Test
    void titleCanBeDraggedOnDesktop() {
        User u = seed();
        page.setViewportSize(1280, 900);
        loginAs(u.getUsername(), "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        dragSectionBAboveSectionA();
        assertSectionBFirstAndSaved(u);
    }

    // Clicking a title shows only that section; the back bar must appear right
    // above the timeline (it used to land above the aircraft-info table).
    @Test
    void clickingTitleFiltersAndBackButtonRestoresFullList() {
        User u = seed();
        page.setViewportSize(1280, 900);
        loginAs(u.getUsername(), "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        page.locator("tr.title-row").nth(1).locator("td.title-cell").click();
        Locator bar = page.locator(".section-filter-bar");
        assertTrue(bar.isVisible(), "Back bar should show while a section is filtered");
        assertTrue(bar.innerText().contains("Section B"), "Bar should name the section");
        assertTrue((Boolean) page.evaluate(
            "() => document.querySelector('.section-filter-bar').nextElementSibling.id === 'sortable-info-table'"),
            "Back bar should sit directly above the Service Timeline table");
        assertTrue(!page.locator("tr.title-row").nth(0).isVisible(), "Other sections should be hidden");

        page.locator(".back-to-full-list-button").click();
        assertTrue(page.locator("tr.title-row").nth(0).isVisible(), "Full list should be back");
        assertEquals(0, page.locator(".section-filter-bar").count(), "Bar should be removed");
    }

    @Test
    void titleCanBeDraggedOnMobile() {
        User u = seed();
        page.setViewportSize(390, 844);
        loginAs(u.getUsername(), "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        dragSectionBAboveSectionA();
        assertSectionBFirstAndSaved(u);
    }
}
