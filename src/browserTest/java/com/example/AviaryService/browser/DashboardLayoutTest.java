package com.example.AviaryService.browser;

import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Regression tests for the dashboard's table layout. Covers:
 *  - The Add submit button is exactly the size of its parent <form>
 *    (the "button overlapping form" bug).
 *  - The Item textarea starts at one-line height for short content
 *    (the "unnecessarily big" bug).
 *  - Column proportions match the post-fix spec.
 *
 * The first test also writes a screenshot of the populated dashboard so
 * a human can sanity-check layout in the build report.
 */
class DashboardLayoutTest extends BrowserTestBase {

    @Autowired
    UserRepository users;

    @Autowired
    ServiceTimelineRepository timelines;

    private List<String> attachConsoleErrorListener() {
        List<String> errors = new ArrayList<>();
        page.onConsoleMessage(msg -> {
            if ("error".equals(msg.type())) errors.add(msg.text());
        });
        return errors;
    }

    private void seedThreeRows(User user) {
        for (int i = 1; i <= 3; i++) {
            ServiceTimeline t = new ServiceTimeline();
            t.setUser(user);
            t.setItem("Row " + i);
            t.setIsTitle(false);
            t.setTimelineOrder(i);
            t.setDescription("Inspect");
            t.setLastDone("2025-10-01");
            t.setDueDate("2026-12-01");
            t.setTimeLeft("");
            timelines.save(t);
        }
    }

    /** Read a getBoundingClientRect property (width, height, x, y...) as px. */
    private double pxOf(com.microsoft.playwright.Locator loc, String prop) {
        Object v = loc.evaluate("(el, prop) => el.getBoundingClientRect()[prop]", prop);
        return ((Number) v).doubleValue();
    }

    @Test
    void addButtonExactlyFillsItsParentForm() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        seedThreeRows(u);

        var errors = attachConsoleErrorListener();
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        var form = page.locator("td.add-cell form");
        var button = page.locator("td.add-cell form button.add-button");

        double formW   = pxOf(form, "width");
        double formH   = pxOf(form, "height");
        double btnW    = pxOf(button, "width");
        double btnH    = pxOf(button, "height");

        // Button must match form dimensions within sub-pixel tolerance.
        if (Math.abs(formW - btnW) > 0.5) {
            throw new AssertionError("Add button width " + btnW + "px does not match form width " + formW + "px");
        }
        if (Math.abs(formH - btnH) > 0.5) {
            throw new AssertionError("Add button height " + btnH + "px does not match form height " + formH + "px");
        }

        // Save a screenshot for human-eyeball verification.
        Path screenshot = Paths.get("build", "reports", "browserTest-screenshots", "dashboard-after-fix.png");
        screenshot.toFile().getParentFile().mkdirs();
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(screenshot)
                .setFullPage(true));

        if (!errors.isEmpty()) throw new AssertionError("Console errors: " + errors);
    }

    @Test
    void shortItemTextareaStartsAtAboutOneLineHeight() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        ServiceTimeline t = new ServiceTimeline();
        t.setUser(u);
        t.setItem("Oil");           // single short token — should fit one line
        t.setIsTitle(false);
        t.setTimelineOrder(1);
        timelines.save(t);

        var errors = attachConsoleErrorListener();
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        var textarea = page.locator(
                "tr.auto-save-row[data-id='" + t.getId() + "'] textarea[name='item']");
        double h = pxOf(textarea, "height");

        // One 13px-line + ~8px padding ≈ 22-32px. Anything above ~40px is
        // the "unnecessarily big" regression we're guarding against.
        if (h > 40.0) {
            throw new AssertionError(
                    "Item textarea is " + h + "px tall for short text — expected ≤40px");
        }

        if (!errors.isEmpty()) throw new AssertionError("Console errors: " + errors);
    }

    @Test
    void itemColumnMatchesDescriptionAndDateColumnsAreWider() {
        // Column proportions spec: Item (18%) should be about the same width as
        // Description (18%) — the free-text Item column must NOT dominate — and
        // the date columns (Last Done / Due Date, 20%) should be at least as
        // wide as Item so their calendar + hours inputs have room.
        String username = seedUser("password");
        User u = users.findByUsername(username);
        seedThreeRows(u);

        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        page.setViewportSize(1400, 900);

        // Sortable head th's: 1=grip 2=Item 3=Description 4=Cycle 5=Last Done
        //                     6=Due Date 7=Time Left 8=Complete 9=Delete
        var ths = page.locator("thead.sortable-head th");
        double itemW        = pxOf(ths.nth(1), "width");
        double descriptionW = pxOf(ths.nth(2), "width");
        double cycleW       = pxOf(ths.nth(3), "width");
        double lastDoneW    = pxOf(ths.nth(4), "width");
        double dueDateW     = pxOf(ths.nth(5), "width");
        double deleteW      = pxOf(ths.nth(8), "width");

        // Item ≈ Description (same percentage). Allow a small tolerance for
        // borders/rounding.
        if (Math.abs(itemW - descriptionW) > 8.0) {
            throw new AssertionError("Item col (" + itemW + ") should be about the same width as Description ("
                    + descriptionW + ")");
        }
        // Date columns must be at least as wide as Item (they're 20% vs 18%).
        if (!(lastDoneW >= itemW - 1)) {
            throw new AssertionError("Last Done col (" + lastDoneW + ") should be >= Item (" + itemW + ")");
        }
        if (!(dueDateW >= itemW - 1)) {
            throw new AssertionError("Due Date col (" + dueDateW + ") should be >= Item (" + itemW + ")");
        }
        // Cycle is allowed to be wide because it hosts a two-row input group.
        if (cycleW < 60) {
            throw new AssertionError("Cycle col (" + cycleW + "px) too narrow for its inputs");
        }
        // Delete column is 4% (with 36px floor). On normal viewports it should
        // be much smaller than Item — guard against percentage flips.
        if (deleteW >= itemW / 2) {
            throw new AssertionError("Delete col (" + deleteW + ") should be much smaller than Item (" + itemW + ")");
        }
        if (deleteW < 36) {
            throw new AssertionError("Delete col (" + deleteW + "px) below 36px floor");
        }
    }

    @Test
    void dateInputsStayInsideTheirCellsWhenWindowIsNarrow() {
        // Regression: when the window narrowed, the calendar (mm/dd/yyyy) and
        // hours inputs in Last Done / Due Date used to collapse and spill out
        // of their cell, hiding behind neighbouring columns. min-width floors
        // on the inputs + columns must keep each field fully inside its own td.
        String username = seedUser("password");
        User u = users.findByUsername(username);

        ServiceTimeline t = new ServiceTimeline();
        t.setUser(u);
        t.setItem("Magneto");
        t.setIsTitle(false);
        t.setTimelineOrder(1);
        t.setDescription("Inspect");
        // Both a date and an hours value so each cell renders both inputs.
        t.setLastDone("2025-10-01 100.5");
        t.setDueDate("2026-12-01 250");
        t.setTimeLeft("");
        timelines.save(t);

        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        // Narrow, but still above the 960px card breakpoint (desktop table).
        page.setViewportSize(1000, 800);

        // td:nth-child(5) = Last Done, td:nth-child(6) = Due Date.
        int[] tdIndexes = { 5, 6 };
        for (int td : tdIndexes) {
            var cell = page.locator(
                    "tr.auto-save-row[data-id='" + t.getId() + "'] td:nth-child(" + td + ")");
            double[] cellRect = rectOf(cell);

            for (String type : new String[] { "input[type='date']", "input[type='text'].extra-input" }) {
                var input = cell.locator(type);
                double[] r = rectOf(input);
                // Fully inside the cell horizontally (allow ~1px AA slack).
                if (r[0] < cellRect[0] - 1.0) {
                    throw new AssertionError("td " + td + " " + type + " left edge " + r[0]
                            + " spills left of cell " + cellRect[0]);
                }
                if (r[0] + r[2] > cellRect[0] + cellRect[2] + 1.0) {
                    throw new AssertionError("td " + td + " " + type + " right edge " + (r[0] + r[2])
                            + " spills past cell right " + (cellRect[0] + cellRect[2]));
                }
                // And actually has a usable width.
                if (r[2] < 60.0) {
                    throw new AssertionError("td " + td + " " + type + " too narrow (" + r[2] + "px) — would hide content");
                }
            }
        }
    }

    /** Read getBoundingClientRect into [x, y, w, h]. */
    private double[] rectOf(com.microsoft.playwright.Locator loc) {
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

    @Test
    void iconsInGripCompleteAndDeleteCellsAreVerticallyCentered() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        // Seed a row whose Cycle inputs (two-row) push the row taller than
        // a single icon — that's the regression case where top-aligned icons
        // looked stuck to the top.
        seedThreeRows(u);

        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        var row = page.locator("tr.auto-save-row").first();
        // Compare each icon's vertical center against the row's vertical
        // center; allow ±4px slack for borders/padding rounding.
        double[] rowRect    = rectOf(row);
        double rowMid       = rowRect[1] + rowRect[3] / 2.0;

        java.util.Map<String, com.microsoft.playwright.Locator> icons = new java.util.LinkedHashMap<>();
        icons.put("grip",     row.locator(".grip-icon"));
        icons.put("complete", row.locator(".complete-btn"));
        icons.put("delete",   row.locator(".delete-icon"));

        for (var entry : icons.entrySet()) {
            double[] r = rectOf(entry.getValue());
            double iconMid = r[1] + r[3] / 2.0;
            if (Math.abs(iconMid - rowMid) > 4.0) {
                throw new AssertionError(entry.getKey() + " icon mid " + iconMid
                        + " differs from row mid " + rowMid + " by more than 4px");
            }
        }
    }

    @Test
    void addRowDescriptionChevronSitsInsideTheBorderedWrapper() {
        // Add-row's description dropdown should mirror the Last Done / Due
        // Date pattern: chevron INSIDE the bordered box, not floating
        // outside. We assert the chevron's bounding rect is fully contained
        // by the .custom-dropdown's bounding rect.
        String username = seedUser("password");
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        var wrapper = page.locator(".add-row .custom-dropdown").first();
        var chevron = wrapper.locator(".trigger-dropdown");

        double[] w = rectOf(wrapper);
        double[] c = rectOf(chevron);

        // chevron x and y must be inside wrapper; right/bottom edges too.
        if (!(c[0] >= w[0] - 0.5 && c[1] >= w[1] - 0.5
                && c[0] + c[2] <= w[0] + w[2] + 0.5
                && c[1] + c[3] <= w[1] + w[3] + 0.5)) {
            throw new AssertionError("Chevron rect " + java.util.Arrays.toString(c)
                    + " is not contained by wrapper rect " + java.util.Arrays.toString(w));
        }
    }

    @Test
    void timeLeftColumnIsSizedToContentNotAFatFixedWidth() {
        // Regression: Time Left used to be a fixed 16% of the table, leaving it
        // far wider than its content and starving the other columns. After the
        // switch to table-layout:auto with no width hint on Time Left, the
        // column should size to its content (the "TIME LEFT" header or the
        // widest data line) — clearly narrower than Item/Description and well
        // below the old ~16% slab.
        String username = seedUser("password");
        User u = users.findByUsername(username);

        // A long-overdue calendar date yields a wide value like
        // "9600 days overdue" — the widest realistic Time Left content.
        ServiceTimeline t = new ServiceTimeline();
        t.setUser(u);
        t.setItem("Engine");
        t.setIsTitle(false);
        t.setTimelineOrder(1);
        t.setDescription("Inspect");
        t.setLastDone("2000-01-01");
        t.setDueDate("2000-01-01");
        t.setTimeLeft("");
        timelines.save(t);

        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        // Wide viewport makes the distinction unambiguous: at 1600px the old
        // 16% column would be ~250px, while content sizing keeps it <200px.
        page.setViewportSize(1600, 900);

        // The JS-computed Time Left must actually be populated and wide.
        var timeLeftCell = page.locator(
                "tr.auto-save-row[data-id='" + t.getId() + "'] .time-left");
        String tlText = timeLeftCell.textContent().trim();
        if (!tlText.contains("overdue")) {
            throw new AssertionError("Expected an 'overdue' Time Left value, got: '" + tlText + "'");
        }

        var ths = page.locator("thead.sortable-head th");
        double itemW        = pxOf(ths.nth(1), "width");
        double descriptionW = pxOf(ths.nth(2), "width");
        double timeLeftW     = pxOf(ths.nth(6), "width");

        if (!(timeLeftW < itemW)) {
            throw new AssertionError("Time Left col (" + timeLeftW + ") should be narrower than Item (" + itemW + ")");
        }
        if (!(timeLeftW < descriptionW)) {
            throw new AssertionError("Time Left col (" + timeLeftW + ") should be narrower than Description (" + descriptionW + ")");
        }
        // Content-sized, not the old fat ~16% slab (~250px @1600w).
        if (timeLeftW >= 200) {
            throw new AssertionError("Time Left col (" + timeLeftW + "px) is too wide — expected content-sized (<200px)");
        }
        // Floor: must still fit the "TIME LEFT" header on one line.
        if (timeLeftW < 60) {
            throw new AssertionError("Time Left col (" + timeLeftW + "px) is below the header floor");
        }
    }

    @Test
    void calendarAlwaysStacksAboveHoursRegardlessOfAddOrder() {
        // The Last Done / Due Date cells let the user add a Calendar (date) and
        // a Clock (hours) input via a +/- menu. Requirement: the inputs always
        // stack vertically with Calendar on TOP and Hours on the BOTTOM, no
        // matter which was added first; removing Calendar moves Hours back up.
        String username = seedUser("password");
        User u = users.findByUsername(username);

        // Empty Last Done / Due Date so the row starts with no inputs — just
        // the chevron — and we drive the +/- menu ourselves.
        ServiceTimeline t = new ServiceTimeline();
        t.setUser(u);
        t.setItem("Battery");
        t.setIsTitle(false);
        t.setTimelineOrder(1);
        t.setDescription("Inspect");
        t.setLastDone("");
        t.setDueDate("");
        t.setTimeLeft("");
        timelines.save(t);

        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        var cell = page.locator(
                "tr.auto-save-row[data-id='" + t.getId() + "'] td:nth-child(5) .input-with-dropdown");

        // Add CLOCK first (the harder case — added first but must end on bottom).
        cell.locator(".trigger-dropdown").click();
        cell.locator(".add-type[data-type='clock']").click();
        // Then add CALENDAR second — it must jump above the hours input.
        cell.locator(".trigger-dropdown").click();
        cell.locator(".add-type[data-type='calendar']").click();

        var dateInput  = cell.locator("input[type='date'].extra-input");
        var hoursInput = cell.locator("input[type='text'].extra-input");

        double[] dateRect  = rectOf(dateInput);
        double[] hoursRect = rectOf(hoursInput);

        // Stacked vertically: same left edge (within tolerance), calendar above.
        if (Math.abs(dateRect[0] - hoursRect[0]) > 2.0) {
            throw new AssertionError("Inputs not vertically stacked — date x=" + dateRect[0]
                    + ", hours x=" + hoursRect[0]);
        }
        if (!(dateRect[1] < hoursRect[1])) {
            throw new AssertionError("Calendar (y=" + dateRect[1] + ") should sit ABOVE hours (y="
                    + hoursRect[1] + ") even though hours was added first");
        }

        // Remove CALENDAR — hours must move up into the top slot.
        cell.locator(".trigger-dropdown").click();
        cell.locator(".add-type[data-type='calendar']").click();

        if (dateInput.count() != 0) {
            throw new AssertionError("Calendar input should be gone after removal");
        }
        double hoursYAfter = rectOf(hoursInput)[1];
        if (!(hoursYAfter < hoursRect[1] + 0.5)) {
            throw new AssertionError("Hours should move up after calendar removed — was y="
                    + hoursRect[1] + ", now y=" + hoursYAfter);
        }
    }

    @Test
    void addButtonScalesWithScreenWidth() {
        // The icon columns are 4% each → on wider viewports the add-cell
        // (colspan=2) gets wider. Verify by measuring at two viewport sizes.
        String username = seedUser("password");
        User u = users.findByUsername(username);
        seedThreeRows(u);

        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        page.setViewportSize(1000, 800);
        double narrowBtn = pxOf(page.locator("td.add-cell button.add-button"), "width");

        page.setViewportSize(1600, 800);
        double wideBtn = pxOf(page.locator("td.add-cell button.add-button"), "width");

        if (!(wideBtn > narrowBtn)) {
            throw new AssertionError("Add button should grow with screen width — "
                    + "got " + narrowBtn + "px @1000w and " + wideBtn + "px @1600w");
        }
    }
}
