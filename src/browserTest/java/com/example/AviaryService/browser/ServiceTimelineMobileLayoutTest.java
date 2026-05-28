package com.example.AviaryService.browser;

import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the mobile service-timeline layout redesign:
 *
 *  - each saved item card lays out 1 / 2-2 / 1 (Item headline full width;
 *    Description|Last Done; Cycle|Due Date; Time Left full width), so the left
 *    column is Description+Cycle and the right column is Last Done+Due Date,
 *  - tapping the Item headline collapses the card to just its name and tapping
 *    again expands it (cards are expanded by default),
 *  - in the Add row, choosing "Title" REMOVES the item-only fields (Description,
 *    Cycle, Last Done, Due Date, Time Left) rather than leaving empty space.
 *
 * Screenshots of the collapsed card and the title-mode add row are dropped into
 * the build report.
 */
class ServiceTimelineMobileLayoutTest extends BrowserTestBase {

    private static final int PHONE_W = 390;
    private static final int PHONE_H = 844;

    @Autowired
    UserRepository users;

    @Autowired
    ServiceTimelineRepository timelines;

    private void seedData(User user) {
        ServiceTimeline title = new ServiceTimeline();
        title.setUser(user);
        title.setItem("ENGINE");
        title.setIsTitle(true);
        title.setTimelineOrder(0);
        timelines.save(title);

        ServiceTimeline item = new ServiceTimeline();
        item.setUser(user);
        item.setItem("Annual Inspection");
        item.setIsTitle(false);
        item.setTimelineOrder(1);
        item.setDescription("Inspect");
        item.setCycleCalendarValue(12);
        item.setCycleCalendarUnit("MONTHS");
        item.setCycleHours(100.0);
        item.setLastDone("2025-06-01");
        item.setDueDate("2026-06-01 2450.0");
        item.setTimeLeft("11 mo\n86.0 hrs");
        timelines.save(item);
    }

    private double[] rectOf(Locator loc) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> r = (java.util.Map<String, Object>) loc.evaluate(
                "el => { const r = el.getBoundingClientRect();"
                + " return { x: r.x, y: r.y, w: r.width, h: r.height }; }");
        return new double[] {
                ((Number) r.get("x")).doubleValue(),
                ((Number) r.get("y")).doubleValue(),
                ((Number) r.get("w")).doubleValue(),
                ((Number) r.get("h")).doubleValue()
        };
    }

    private void openDashboardOnPhone() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        seedData(u);
        page.setViewportSize(PHONE_W, PHONE_H);
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        // Last Done / Due Date inputs are injected by JS from the row's
        // data-* attributes on load — wait for one before measuring.
        page.locator("tr.auto-save-row td[data-label='Due Date'] input.extra-input")
                .first().waitFor();
    }

    @Test
    void cardLaysOutOneTwoTwoOne() {
        openDashboardOnPhone();

        Locator row = page.locator("tr.auto-save-row").first();
        double[] rowR = rectOf(row);

        double[] item = rectOf(row.locator("td[data-label='Item']"));
        double[] desc = rectOf(row.locator("td[data-label='Description']"));
        double[] cyc  = rectOf(row.locator("td[data-label='Cycle']"));
        double[] last = rectOf(row.locator("td[data-label='Last Done']"));
        double[] due  = rectOf(row.locator("td[data-label='Due Date']"));
        double[] time = rectOf(row.locator("td[data-label='Time Left']"));

        // Item is a full-width headline at the top.
        assertTrue(item[2] > rowR[2] * 0.8,
                "Item should span (nearly) the full card width — was " + item[2] + " of " + rowR[2]);

        // Row 2: Description (left) and Last Done (right) sit side by side.
        assertTrue(Math.abs(desc[1] - last[1]) < 6,
                "Description and Last Done should share a row (y " + desc[1] + " vs " + last[1] + ")");
        assertTrue(desc[0] < last[0],
                "Description should be in the LEFT column, Last Done in the RIGHT");

        // Row 3: Cycle (left) and Due Date (right) sit side by side, BELOW row 2.
        assertTrue(Math.abs(cyc[1] - due[1]) < 6,
                "Cycle and Due Date should share a row (y " + cyc[1] + " vs " + due[1] + ")");
        assertTrue(cyc[0] < due[0],
                "Cycle should be in the LEFT column, Due Date in the RIGHT");
        assertTrue(cyc[1] > desc[1] + 5,
                "Cycle row should be below the Description row");

        // Left column shares an x; right column shares an x.
        assertTrue(Math.abs(desc[0] - cyc[0]) < 4,
                "Description and Cycle should share the LEFT column x (" + desc[0] + " vs " + cyc[0] + ")");
        assertTrue(Math.abs(last[0] - due[0]) < 4,
                "Last Done and Due Date should share the RIGHT column x (" + last[0] + " vs " + due[0] + ")");

        // Time Left is full width at the bottom of the field block.
        assertTrue(time[2] > rowR[2] * 0.8,
                "Time Left should span (nearly) the full card width — was " + time[2]);
        assertTrue(time[1] > cyc[1] + 5,
                "Time Left should sit below the Cycle / Due Date row");
    }

    @Test
    void tappingItemHeadlineCollapsesAndExpands() {
        openDashboardOnPhone();

        Locator row = page.locator("tr.auto-save-row").first();
        Locator itemCell = row.locator("td[data-label='Item']");
        Locator descCell = row.locator("td[data-label='Description']");
        Locator caret = row.locator("td[data-label='Item'] .card-caret");

        // Expanded by default.
        assertTrue(descCell.isVisible(), "Card should be expanded by default");
        assertFalse(((String) row.getAttribute("class")).contains("collapsed"),
                "Card should not start collapsed");
        String caretExpanded = cssOf(caret, "transform");

        // Tap the caret area on the right of the headline (not the textarea).
        BoundingBox b = itemCell.boundingBox();
        itemCell.click(new Locator.ClickOptions().setPosition(b.width - 8, b.height / 2));

        assertTrue(((String) row.getAttribute("class")).contains("collapsed"),
                "Tapping the headline should collapse the card");
        assertFalse(descCell.isVisible(),
                "Collapsed card should hide everything except the Item name");
        assertNotEquals(caretExpanded, cssOf(caret, "transform"),
                "Collapse arrow should rotate when the card is collapsed");

        Path shot = Paths.get("build", "browsertest-screenshots", "mobile-card-collapsed.png");
        try {
            Files.createDirectories(shot.getParent());
        } catch (Exception ignored) { }
        page.screenshot(new Page.ScreenshotOptions().setPath(shot).setFullPage(true));

        // Tap again (anywhere on the collapsed card) to re-expand.
        itemCell.click(new Locator.ClickOptions().setPosition(b.width - 8, b.height / 2));
        assertFalse(((String) row.getAttribute("class")).contains("collapsed"),
                "Tapping again should re-expand the card");
        assertTrue(descCell.isVisible(), "Re-expanded card should show its fields again");
    }

    @Test
    void addRowTitleModeRemovesItemOnlyFields() {
        openDashboardOnPhone();

        Locator addRow = page.locator(".add-row");
        // Default is "item" mode — fields visible.
        assertTrue(addRow.locator("td[data-label='Cycle']").isVisible(),
                "Cycle field should be visible while adding an Item");

        // Switch to "Title" mode.
        addRow.locator(".row-type-option[data-type='title']").click();

        assertTrue(page.locator("#titleInput input").isVisible(),
                "Title input should be visible in title mode");
        for (String label : new String[] { "Description", "Cycle", "Last Done", "Due Date", "Time Left" }) {
            assertFalse(addRow.locator("td[data-label='" + label + "']").isVisible(),
                    label + " field should be removed (not shown) while adding a Title");
        }

        Path shot = Paths.get("build", "browsertest-screenshots", "mobile-addrow-title-mode.png");
        try {
            Files.createDirectories(shot.getParent());
        } catch (Exception ignored) { }
        page.screenshot(new Page.ScreenshotOptions().setPath(shot).setFullPage(true));

        // Switch back to "Item" — fields return.
        addRow.locator(".row-type-option[data-type='item']").click();
        assertTrue(addRow.locator("td[data-label='Cycle']").isVisible(),
                "Cycle field should return when switching back to Item");
    }

    private String cssOf(Locator loc, String prop) {
        return (String) loc.evaluate("(el, p) => getComputedStyle(el).getPropertyValue(p)", prop);
    }

    @Test
    void addRowUsesSameOneTwoTwoOneGridLayout() {
        openDashboardOnPhone();

        Locator addRow = page.locator(".add-row");
        assertEquals("grid", cssOf(addRow, "display"),
                "Add row should lay out as a CSS grid like the saved cards");

        double[] desc = rectOf(addRow.locator("td[data-label='Description']"));
        double[] cyc  = rectOf(addRow.locator("td[data-label='Cycle']"));
        double[] last = rectOf(addRow.locator("td[data-label='Last Done']"));
        double[] due  = rectOf(addRow.locator("td[data-label='Due Date']"));

        assertTrue(Math.abs(desc[1] - last[1]) < 6,
                "Add row: Description and Last Done should share a row");
        assertTrue(desc[0] < last[0],
                "Add row: Description should be LEFT, Last Done RIGHT");
        assertTrue(Math.abs(cyc[1] - due[1]) < 6,
                "Add row: Cycle and Due Date should share a row");
        assertTrue(cyc[0] < due[0],
                "Add row: Cycle should be LEFT, Due Date RIGHT");
        assertTrue(cyc[1] > desc[1] + 5,
                "Add row: Cycle row should be below the Description row");
    }

    @Test
    void cycleUnitArrowIsRestyledToFaChevron() {
        openDashboardOnPhone();

        Locator unit = page.locator("tr.auto-save-row td[data-label='Cycle'] select.cycle-unit").first();
        // Native select arrow removed...
        String appearance = cssOf(unit, "appearance");
        assertTrue("none".equals(appearance) || "none".equals(cssOf(unit, "-webkit-appearance")),
                "Cycle unit should drop the native select arrow (appearance:none), was " + appearance);
        // ...and replaced by an SVG chevron background, matching the other fields.
        String bg = cssOf(unit, "background-image");
        assertTrue(bg != null && bg.contains("svg"),
                "Cycle unit should show an SVG chevron background, was " + bg);
    }

    @Test
    void cardArrowIsBigFontAwesomeChevron() {
        openDashboardOnPhone();

        Locator caret = page.locator("tr.auto-save-row td[data-label='Item'] .card-caret").first();
        String cls = (String) caret.getAttribute("class");
        assertTrue(cls != null && cls.contains("fa-chevron-down"),
                "Card arrow should be a fa-chevron-down icon, classes: " + cls);
        assertFalse("none".equals(cssOf(caret, "display")),
                "Card arrow should be visible on mobile");
        double size = Double.parseDouble(cssOf(caret, "font-size").replace("px", "").trim());
        assertTrue(size >= 18,
                "Card arrow should be bigger than the field arrows (>=18px), was " + size);
    }

    @Test
    void timeLeftIsHorizontallyCentered() {
        openDashboardOnPhone();
        Locator timeLeft = page.locator("tr.auto-save-row .time-left").first();
        assertEquals("center", cssOf(timeLeft, "text-align"),
                "Time Left should be horizontally centered");
    }

    @Test
    void serviceTimelineNeverOverflowsViewportAcrossBreakpoints() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        seedData(u);
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        int[] widths = { 769, 780, 800, 850, 900, 950, 1000, 1024 };
        StringBuilder over = new StringBuilder();
        for (int w : widths) {
            page.setViewportSize(w, 900);
            page.waitForTimeout(80);
            long scrollW = ((Number) page.evaluate("() => document.documentElement.scrollWidth")).longValue();
            long innerW = ((Number) page.evaluate("() => window.innerWidth")).longValue();
            if (scrollW > innerW + 1) {
                over.append(" [w=").append(w).append(": scrollWidth=").append(scrollW)
                    .append(" > innerWidth=").append(innerW).append("]");
            }
            // Visual evidence at a width that used to overflow with the desktop table.
            if (w == 850) {
                Path shot = Paths.get("build", "browsertest-screenshots", "timeline-850-no-overflow.png");
                try {
                    Files.createDirectories(shot.getParent());
                } catch (Exception ignored) { }
                page.screenshot(new Page.ScreenshotOptions().setPath(shot).setFullPage(true));
            }
        }
        if (over.length() > 0) {
            throw new AssertionError("Service timeline overflows the viewport at:" + over);
        }
    }

    @Test
    void cardCellsHaveSeparatingBorders() {
        openDashboardOnPhone();
        Locator descCell = page.locator("tr.auto-save-row td[data-label='Description']").first();
        assertEquals("solid", cssOf(descCell, "border-bottom-style"),
                "Cells should have a bottom border separating rows");
        assertEquals("solid", cssOf(descCell, "border-right-style"),
                "Left-column cells should have a right border separating columns");
    }

    @Test
    void collapseTogglesInCardModeButNotOnDesktop() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        seedData(u);
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        // Desktop table (>960px): tapping the item must NOT collapse the row.
        page.setViewportSize(1000, 900);
        page.waitForTimeout(80);
        Locator row = page.locator("tr.auto-save-row").first();
        row.locator("td[data-label='Item']").click();
        assertFalse(((String) row.getAttribute("class")).contains("collapsed"),
                "Desktop table rows must not collapse on click");

        // Wide card mode (769–960px): tapping the item title SHOULD collapse it.
        page.setViewportSize(900, 900);
        page.waitForTimeout(80);
        Locator itemCell = row.locator("td[data-label='Item']");
        BoundingBox b = itemCell.boundingBox();
        itemCell.click(new Locator.ClickOptions().setPosition(b.width - 8, b.height / 2));
        assertTrue(((String) row.getAttribute("class")).contains("collapsed"),
                "At 900px (card mode) tapping the item title should collapse the card");
    }

    @Test
    void addRowItemNameIsCentered() {
        openDashboardOnPhone();
        Locator itemInput = page.locator(".add-row td[data-label='Item'] textarea");
        assertEquals("center", cssOf(itemInput, "text-align"),
                "The add-row item name input should be horizontally centered");
    }

    @Test
    void addRowCycleDoesNotOverlapTimeLeft() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        seedData(u);
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        for (int w : new int[] { 390, 700, 900 }) {
            page.setViewportSize(w, 900);
            page.waitForTimeout(80);
            double[] hrsRow = rectOf(page.locator(".add-row td[data-label='Cycle'] .cycle-row").last());
            double[] timeLeft = rectOf(page.locator(".add-row td[data-label='Time Left']"));
            double hrsBottom = hrsRow[1] + hrsRow[3];
            double tlTop = timeLeft[1];
            assertTrue(hrsBottom <= tlTop + 1,
                    "At " + w + "px the cycle hours row (bottom " + hrsBottom
                    + ") must not overlap Time Left (top " + tlTop + ")");
            if (w == 900) {
                Path shot = Paths.get("build", "browsertest-screenshots", "addrow-900-cycle-fixed.png");
                try {
                    Files.createDirectories(shot.getParent());
                } catch (Exception ignored) { }
                page.screenshot(new Page.ScreenshotOptions().setPath(shot).setFullPage(true));
            }
        }
    }

    @Test
    void cycleChevronMatchesFieldChevronSize() {
        openDashboardOnPhone();
        Locator unit = page.locator("tr.auto-save-row td[data-label='Cycle'] select.cycle-unit").first();
        String bgSize = cssOf(unit, "background-size");
        assertTrue(bgSize != null && bgSize.contains("14px"),
                "Cycle chevron should be 14px to match the other field chevrons, was " + bgSize);
    }
}
