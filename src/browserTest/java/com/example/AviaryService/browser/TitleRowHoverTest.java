package com.example.AviaryService.browser;

import com.example.AviaryService.entity.StatusSheetItem;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.StatusSheetItemRepository;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Spec 009: hovering any part of a title row darkens the whole bar (grip,
 * title, empty cell and trash can) together, and only that row.
 */
class TitleRowHoverTest extends BrowserTestBase {

    @Autowired
    StatusSheetItemRepository statusSheetItems;

    private StatusSheetItem save(User user, String text, boolean title, int order) {
        StatusSheetItem t = new StatusSheetItem();
        t.setUser(user);
        t.setAircraft(aircraftOf(user));
        t.setItem(text);
        t.setIsTitle(title);
        t.setStatusSheetOrder(order);
        return statusSheetItems.save(t);
    }

    @SuppressWarnings("unchecked")
    private List<String> cellColors(Locator row) {
        return (List<String>) row.evaluate("r => [...r.querySelectorAll('td')]"
            + ".filter(td => getComputedStyle(td).display !== 'none')"
            + ".map(td => getComputedStyle(td).backgroundColor)");
    }

    @Test
    void wholeTitleRowDarkensTogetherAndOnlyThatRow() {
        String username = seedUser("password");
        User user = userRepository.findByUsername(username);
        StatusSheetItem engine = save(user, "Engine", true, 0);
        save(user, "Oil change", false, 1);
        StatusSheetItem airframe = save(user, "Airframe", true, 2);

        page.setViewportSize(1400, 900);
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        Locator row = page.locator("tr.title-row[data-id='" + engine.getId() + "']");
        Locator other = page.locator("tr.title-row[data-id='" + airframe.getId() + "']");
        page.mouse().move(0, 0);
        List<String> normal = cellColors(row);
        assertEquals(1, normal.stream().distinct().count(), "title bar is one color at rest: " + normal);

        for (String part : new String[] {".title-cell", ".grip-cell", ".delete-cell"}) {
            row.locator(part).hover();
            page.waitForTimeout(100);
            List<String> hovered = cellColors(row);
            assertEquals(1, hovered.stream().distinct().count(), "hovering " + part + ": " + hovered);
            assertNotEquals(normal.get(0), hovered.get(0), "hovering " + part + " darkens the bar");
            assertEquals(normal, cellColors(other), "the other title row stays normal");
        }

        page.mouse().move(0, 0);
        page.waitForTimeout(100);
        assertEquals(normal, cellColors(row), "back to normal after the mouse leaves");
    }
}
