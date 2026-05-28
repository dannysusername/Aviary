package com.example.AviaryService.browser;

import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Regression tests for the "continuous grid" service-timeline redesign.
 *
 * The redesign made every cell's field fill its whole grid cell (borderless,
 * vertically centered) so there is no dead gap above/below short controls when
 * the two-line Cycle column makes a row tall. It also:
 *  - tucks the "hrs" suffix INSIDE the Cycle cell (it used to spill out),
 *  - splits the Cycle cell into two hairline-separated halves,
 *  - shows an "Add date / hrs" placeholder in empty Last Done / Due Date cells,
 *  - makes the Item/Title picker a segmented control with EQUAL-width segments,
 *  - pads the Add button inside its cell so it never touches the cell border.
 *
 * These tests guard each of those against regressions and drop before/after
 * screenshots (saved row + add row) at two desktop widths into the build report.
 */
class ServiceTimelineGridTest extends BrowserTestBase {

    @Autowired
    UserRepository users;

    @Autowired
    ServiceTimelineRepository timelines;

    /** A title row + one item row whose two-line Cycle makes the row tall. */
    private long seedTallCycleRow(User user) {
        ServiceTimeline title = new ServiceTimeline();
        title.setUser(user);
        title.setItem("ENGINE - LEFT");
        title.setIsTitle(true);
        title.setTimelineOrder(1);
        timelines.save(title);

        ServiceTimeline t = new ServiceTimeline();
        t.setUser(user);
        t.setItem("Oil & filter change");
        t.setIsTitle(false);
        t.setTimelineOrder(2);
        t.setDescription("Inspect");
        t.setCycleCalendarValue(50);
        t.setCycleCalendarUnit("DAYS");
        t.setCycleHours(25.0);
        t.setLastDone("");   // empty → exercises the "Add date / hrs" placeholder
        t.setDueDate("");
        t.setTimeLeft("");
        timelines.save(t);
        return t.getId();
    }

    /** getBoundingClientRect into [x, y, w, h]. */
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

    private String beforeContent(Locator loc) {
        return (String) loc.evaluate("el => getComputedStyle(el, '::before').content");
    }

    @Test
    void hrsSuffixStaysInsideTheCycleCell() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        long id = seedTallCycleRow(u);

        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        page.setViewportSize(1280, 800);

        var cell = page.locator("tr.auto-save-row[data-id='" + id + "'] td:nth-child(4)");
        var hrs  = page.locator("tr.auto-save-row[data-id='" + id + "'] .cycle-unit-label");

        double[] cellR = rectOf(cell);
        double[] hrsR  = rectOf(hrs);

        // The "hrs" label must sit fully inside the Cycle cell horizontally.
        if (hrsR[0] + hrsR[2] > cellR[0] + cellR[2] + 0.5) {
            throw new AssertionError("'hrs' right edge " + (hrsR[0] + hrsR[2])
                    + " spills past Cycle cell right edge " + (cellR[0] + cellR[2]));
        }
        if (hrsR[0] < cellR[0] - 0.5) {
            throw new AssertionError("'hrs' left edge spills left of the Cycle cell");
        }
    }

    @Test
    void cycleCellIsSplitIntoTwoHairlineHalves() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        long id = seedTallCycleRow(u);

        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        var rows = page.locator("tr.auto-save-row[data-id='" + id + "'] td:nth-child(4) .cycle-row");
        if (rows.count() != 2) {
            throw new AssertionError("Cycle cell should have exactly 2 rows, found " + rows.count());
        }
        // First half carries the hairline divider; second half does not.
        String firstBorder = (String) rows.nth(0).evaluate(
                "el => getComputedStyle(el).borderBottomStyle");
        if ("none".equals(firstBorder)) {
            throw new AssertionError("First cycle row should have a hairline divider (border-bottom)");
        }
    }

    @Test
    void shortFieldCellsAreVerticallyCenteredWithNoTopGap() {
        // The whole point of the redesign: in a tall row, a single short control
        // (Description) must be vertically CENTERED in its cell, not pinned to the
        // top with dead space below. Assert its vertical center is within a few px
        // of the row's vertical center.
        String username = seedUser("password");
        User u = users.findByUsername(username);
        long id = seedTallCycleRow(u);

        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        page.setViewportSize(1280, 800);

        var row  = page.locator("tr.auto-save-row[data-id='" + id + "']");
        var desc = page.locator("tr.auto-save-row[data-id='" + id + "'] td:nth-child(3) .selected-option");

        double[] rowR  = rectOf(row);
        double[] descR = rectOf(desc);

        double rowMid  = rowR[1] + rowR[3] / 2.0;
        double descMid = descR[1] + descR[3] / 2.0;

        // Tall row (two-line Cycle) — the control must be centered, not top-pinned.
        if (rowR[3] < 48) {
            throw new AssertionError("Row should be tall (two-line Cycle), was " + rowR[3] + "px");
        }
        if (Math.abs(descMid - rowMid) > 8.0) {
            throw new AssertionError("Description control mid " + descMid
                    + " is not centered in row (mid " + rowMid + ") — looks top-pinned with a gap");
        }
    }

    @Test
    void emptyDateCellShowsAddDatePlaceholder() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        long id = seedTallCycleRow(u);   // lastDone / dueDate are empty

        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        var dueWrapper = page.locator(
                "tr.auto-save-row[data-id='" + id + "'] td:nth-child(6) .input-with-dropdown");
        String content = beforeContent(dueWrapper);
        if (content == null || !content.contains("Add date")) {
            throw new AssertionError("Empty Due Date cell should show an 'Add date' placeholder, got: " + content);
        }
    }

    @Test
    void itemTitleToggleSegmentsAreEqualWidth() {
        String username = seedUser("password");
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        page.setViewportSize(1280, 800);

        var options = page.locator(".add-row .row-type-option");
        if (options.count() != 2) {
            throw new AssertionError("Expected 2 row-type segments, found " + options.count());
        }
        double itemW  = rectOf(options.nth(0))[2];
        double titleW = rectOf(options.nth(1))[2];
        if (Math.abs(itemW - titleW) > 1.0) {
            throw new AssertionError("Item/Title segments should be equal width — Item " + itemW
                    + "px vs Title " + titleW + "px");
        }
    }

    @Test
    void addButtonIsPaddedInsideItsCellNotTouchingTheBorder() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        seedTallCycleRow(u);

        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        page.setViewportSize(1280, 800);

        var cell   = page.locator(".add-row td.add-cell");
        var button = page.locator(".add-row td.add-cell button.add-button");
        double[] cellR = rectOf(cell);
        double[] btnR  = rectOf(button);

        // There must be real horizontal padding on both sides (≥6px each).
        double leftPad  = btnR[0] - cellR[0];
        double rightPad = (cellR[0] + cellR[2]) - (btnR[0] + btnR[2]);
        if (leftPad < 6.0 || rightPad < 6.0) {
            throw new AssertionError("Add button not padded inside its cell — leftPad=" + leftPad
                    + " rightPad=" + rightPad);
        }
    }

    @Test
    void screenshotsSavedAndAddRowsAtMultipleDesktopWidths() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        seedTallCycleRow(u);

        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        for (int w : new int[] { 1280, 1024, 980 }) {
            page.setViewportSize(w, 800);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            Path shot = Paths.get("build", "reports", "browserTest-screenshots",
                    "timeline-grid-" + w + ".png");
            shot.toFile().getParentFile().mkdirs();
            page.screenshot(new Page.ScreenshotOptions().setPath(shot).setFullPage(true));
        }
    }
}
