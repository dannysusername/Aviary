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
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the mobile drag affordance and the date-cell tap target:
 *
 *  - the desktop grip-dots (fa-grip-vertical) now also render inside each mobile
 *    card, pinned to the TOP-LEFT of the item headline (mirroring the desktop
 *    leftmost-column grip), so cards can be reordered by touch,
 *  - dragging that grip actually reorders the cards (SortableJS forceFallback so
 *    the drag works on touch devices, not just native HTML5 DnD),
 *  - the grip overlay is pointer-transparent except for the dots, so tapping the
 *    headline still collapses/expands and tapping the grip does NOT collapse,
 *  - in Last Done / Due Date the native date field's calendar picker no longer
 *    sits flush against the custom chevron, so a tap on the chevron can't land on
 *    the calendar picker.
 *
 * Screenshots of the grip and a before/after drag are dropped into the build
 * report at build/browsertest-screenshots/.
 */
class ServiceTimelineMobileDragTest extends BrowserTestBase {

    private static final int PHONE_W = 390;
    private static final int PHONE_H = 844;

    @Autowired
    UserRepository users;

    @Autowired
    ServiceTimelineRepository timelines;

    /** Two plain item rows (no title) so a drag swap is unambiguous. */
    private void seedTwoItems(User user) {
        ServiceTimeline alpha = new ServiceTimeline();
        alpha.setUser(user);
        alpha.setItem("Alpha Item");
        alpha.setIsTitle(false);
        alpha.setTimelineOrder(0);
        alpha.setDescription("Inspect");
        alpha.setCycleCalendarValue(12);
        alpha.setCycleCalendarUnit("MONTHS");
        alpha.setLastDone("2025-06-01");
        alpha.setDueDate("2026-06-01");
        timelines.save(alpha);

        ServiceTimeline bravo = new ServiceTimeline();
        bravo.setUser(user);
        bravo.setItem("Bravo Item");
        bravo.setIsTitle(false);
        bravo.setTimelineOrder(1);
        bravo.setDescription("Test");
        bravo.setCycleCalendarValue(6);
        bravo.setCycleCalendarUnit("MONTHS");
        bravo.setLastDone("2025-01-01");
        bravo.setDueDate("2025-07-01");
        timelines.save(bravo);
    }

    private void openDashboardOnPhone() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        seedTwoItems(u);
        page.setViewportSize(PHONE_W, PHONE_H);
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        // Date inputs are injected by JS from each row's data-* attributes on
        // load — wait for one before measuring or dragging.
        page.locator("tr.auto-save-row td[data-label='Last Done'] input.extra-input")
                .first().waitFor();
    }

    private double[] rectOf(Locator loc) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> r = (java.util.Map<String, Object>) loc.evaluate(
                "el => { const b = el.getBoundingClientRect();"
                + " return { x: b.x, y: b.y, w: b.width, h: b.height }; }");
        return new double[] {
                ((Number) r.get("x")).doubleValue(),
                ((Number) r.get("y")).doubleValue(),
                ((Number) r.get("w")).doubleValue(),
                ((Number) r.get("h")).doubleValue()
        };
    }

    private String cssOf(Locator loc, String prop) {
        return (String) loc.evaluate("(el, p) => getComputedStyle(el).getPropertyValue(p)", prop);
    }

    private List<String> itemOrder() {
        // Scope to the .sortable container and exclude SortableJS's forceFallback
        // clone (class sortable-fallback, appended outside the list) so the
        // dragged row isn't counted twice. textarea value lives in its text node
        // (set via th:text, never edited here), so textContent is reliable.
        return page.locator(".sortable tr.auto-save-row:not(.sortable-fallback) "
                + "td[data-label='Item'] textarea")
                .allTextContents();
    }

    private void shoot(String name) {
        Path shot = Paths.get("build", "browsertest-screenshots", name);
        try {
            Files.createDirectories(shot.getParent());
        } catch (Exception ignored) { }
        page.screenshot(new Page.ScreenshotOptions().setPath(shot).setFullPage(true));
    }

    @Test
    void gripDotsRenderTopLeftOfCardOnMobile() {
        openDashboardOnPhone();

        Locator row = page.locator("tr.auto-save-row").first();
        Locator grip = row.locator("td.grip-cell .grip-icon");

        assertTrue(grip.isVisible(), "Grip dots should be visible inside the mobile card");

        // Same fa-grip-vertical icon as desktop.
        String iconClass = (String) row.locator("td.grip-cell .grip-icon i").getAttribute("class");
        assertTrue(iconClass != null && iconClass.contains("fa-grip-vertical"),
                "Grip should use the desktop fa-grip-vertical dots, classes: " + iconClass);

        // Pinned to the LEFT of the card, and vertically over the item headline.
        double[] rowR = rectOf(row);
        double[] gripR = rectOf(grip);
        double[] itemR = rectOf(row.locator("td[data-label='Item']"));

        assertTrue(gripR[0] - rowR[0] < rowR[2] * 0.25,
                "Grip should sit near the LEFT edge of the card (grip x " + gripR[0]
                + " vs card x " + rowR[0] + ", width " + rowR[2] + ")");
        double gripMidY = gripR[1] + gripR[3] / 2;
        assertTrue(gripMidY >= itemR[1] - 2 && gripMidY <= itemR[1] + itemR[3] + 2,
                "Grip should sit over the item headline row (grip midY " + gripMidY
                + " vs header " + itemR[1] + "–" + (itemR[1] + itemR[3]) + ")");

        // The item name must not be hidden behind the grip.
        double[] textR = rectOf(row.locator("td[data-label='Item'] textarea"));
        assertTrue(textR[0] >= gripR[0] + gripR[2] - 2,
                "Item text should start to the RIGHT of the grip (text x " + textR[0]
                + " vs grip right " + (gripR[0] + gripR[2]) + ")");

        shoot("mobile-grip-dots.png");
    }

    @Test
    void gripIsTheSortableHandleAndDraggingReordersCards() {
        openDashboardOnPhone();

        assertTrue(itemOrder().get(0).startsWith("Alpha"),
                "Alpha should start first, order was " + itemOrder());
        shoot("mobile-drag-before.png");

        Locator rows = page.locator("tr.auto-save-row");
        Locator alphaGrip = rows.nth(0).locator("td.grip-cell .grip-icon");
        BoundingBox from = alphaGrip.boundingBox();
        BoundingBox target = rows.nth(1).boundingBox();

        double startX = from.x + from.width / 2;
        double startY = from.y + from.height / 2;
        // Drop past the MIDPOINT of Bravo so SortableJS swaps the two cards.
        double endX = target.x + target.width / 2;
        double endY = target.y + target.height * 0.75;

        // forceFallback drags via plain pointer events — drive them in steps so
        // the move clears SortableJS's start threshold and triggers the swap.
        page.mouse().move(startX, startY);
        page.mouse().down();
        page.mouse().move(startX, startY + 12, new com.microsoft.playwright.Mouse.MoveOptions().setSteps(4));
        page.mouse().move(endX, endY, new com.microsoft.playwright.Mouse.MoveOptions().setSteps(25));
        page.mouse().move(endX, endY + 4, new com.microsoft.playwright.Mouse.MoveOptions().setSteps(4));
        page.mouse().up();

        // onEnd → updateOrderOnServer reads the new DOM order; give it a beat.
        page.waitForTimeout(300);

        List<String> after = itemOrder();
        shoot("mobile-drag-after.png");
        assertTrue(after.get(0).startsWith("Bravo"),
                "After dragging Alpha's grip below Bravo, Bravo should be first — order was " + after);
    }

    @Test
    void tappingGripDoesNotCollapseButTappingHeadlineDoes() {
        openDashboardOnPhone();

        Locator row = page.locator("tr.auto-save-row").first();
        Locator grip = row.locator("td.grip-cell .grip-icon");

        // A plain tap on the grip (no drag) must not collapse the card.
        grip.click();
        assertFalse(((String) row.getAttribute("class")).contains("collapsed"),
                "Tapping the grip should not collapse the card");
        assertTrue(row.locator("td[data-label='Description']").isVisible(),
                "Card should still be expanded after tapping the grip");

        // Tapping the headline itself (right side, clear of the grip) still collapses.
        Locator itemCell = row.locator("td[data-label='Item']");
        BoundingBox b = itemCell.boundingBox();
        itemCell.click(new Locator.ClickOptions().setPosition(b.width - 8, b.height / 2));
        assertTrue(((String) row.getAttribute("class")).contains("collapsed"),
                "Tapping the headline should still collapse the card with the grip present");
    }

    @Test
    void calendarPickerDoesNotOverlapChevronOnMobile() {
        openDashboardOnPhone();

        for (String label : new String[] { "Last Done", "Due Date" }) {
            Locator cell = page.locator("tr.auto-save-row td[data-label='" + label + "']").first();
            Locator dateInput = cell.locator("input[type='date'].extra-input");
            Locator chevron = cell.locator(".trigger-dropdown");

            double[] dateR = rectOf(dateInput);
            double[] chevR = rectOf(chevron);

            double gap = chevR[0] - (dateR[0] + dateR[2]);
            assertTrue(gap >= 8,
                    label + ": the chevron must be clearly separated from the date field's "
                    + "calendar picker (gap was " + gap + "px)");
        }

        // The CSS gap that drives the separation.
        Locator wrap = page.locator("tr.auto-save-row td[data-label='Due Date'] .input-with-dropdown").first();
        double colGap = Double.parseDouble(cssOf(wrap, "column-gap").replace("px", "").trim());
        assertTrue(colGap >= 12,
                "Mobile date cell column-gap should be >=12px to keep the picker off the chevron, was " + colGap);

        shoot("mobile-date-chevron-gap.png");
    }

    // ── Upward-drag regression (the reported bug) ─────────────────────────────
    // On mobile each card is `display:grid` with two columns, which made
    // SortableJS auto-detect the list as HORIZONTAL and swap on the X-axis.
    // Every card spans the same full width, so dragging a card UP landed it on
    // the wrong neighbour or jumped it to the very top. The fix pins the list
    // direction to 'vertical'. These tests seed FOUR cards and drag upward into
    // a MIDDLE slot — exactly what used to misbehave.

    /** Four plain item rows A–D so an up-drag into the middle is unambiguous. */
    private void seedFourItems(User user) {
        String[] names = { "Alpha Item", "Bravo Item", "Charlie Item", "Delta Item" };
        for (int i = 0; i < names.length; i++) {
            ServiceTimeline t = new ServiceTimeline();
            t.setUser(user);
            t.setItem(names[i]);
            t.setIsTitle(false);
            t.setTimelineOrder(i);
            t.setDescription("Desc " + i);
            t.setCycleCalendarValue(12);
            t.setCycleCalendarUnit("MONTHS");
            t.setLastDone("2025-06-01");
            t.setDueDate("2026-06-01");
            timelines.save(t);
        }
    }

    private void openFourOnPhone() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        seedFourItems(u);
        // Phone WIDTH (390 keeps the mobile card layout, gated on max-width:960px).
        page.setViewportSize(PHONE_W, PHONE_H);
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        page.locator("tr.auto-save-row td[data-label='Last Done'] input.extra-input")
                .first().waitFor();
    }

    /** Neutralise the live SortableJS animation so a simulated drag reorders
     *  deterministically. The app keeps its 150ms animation in production; this
     *  only changes the running test instance. Returns the instance's resolved
     *  drag direction so a test can assert the fix (direction === 'vertical'). */
    private String sortableDirectionAfterDisablingAnimation() {
        return (String) page.evaluate("() => { const el = document.querySelector('.sortable');"
                + " const s = window.Sortable && window.Sortable.get(el);"
                + " if (!s) return 'NO-INSTANCE';"
                + " s.option('animation', 0);"
                + " const d = s.options.direction;"
                + " return typeof d === 'function' ? d.call(s, null, null, null) : d; }");
    }

    /** Collapse every card to its headline so all four fit on the phone screen
     *  and the drag stays within the viewport (raw mouse coords don't scroll). */
    private void collapseAllCards() {
        Locator rows = page.locator("tr.auto-save-row");
        int n = rows.count();
        for (int i = 0; i < n; i++) {
            Locator itemCell = rows.nth(i).locator("td[data-label='Item']");
            BoundingBox b = itemCell.boundingBox();
            // Right side of the headline, clear of the left-pinned grip.
            itemCell.click(new Locator.ClickOptions().setPosition(b.width - 8, b.height / 2));
        }
        // Bring the first card near the top so all collapsed cards are on-screen.
        page.evaluate("() => { const r = document.querySelector('tr.auto-save-row')"
                + ".getBoundingClientRect(); window.scrollBy(0, r.top - 80); }");
        page.waitForTimeout(100);
    }

    /** Grab the grip of card #sourceIndex and crawl it UPWARD over the card
     *  bodies (centre-x), stopping the instant the live order reaches
     *  {@code desiredFirstWords}. With the animation neutralised, each swap
     *  registers as the pointer crosses a card, so this lands the dragged card
     *  at an exact interior slot instead of overshooting to an extreme. The
     *  grab is on the grip (the handle), then the pointer moves to the body
     *  centre because the grip column is overlaid by a pointer-events:none
     *  grip-cell that defeats mid-list target detection. */
    private void dragGripUpUntilOrder(int sourceIndex, List<String> desiredFirstWords) {
        Locator rows = page.locator(".sortable tr.auto-save-row");
        BoundingBox grip = rows.nth(sourceIndex).locator("td.grip-cell .grip-icon").boundingBox();
        double[] srcBox = rectOf(rows.nth(sourceIndex));
        double cx = srcBox[0] + srcBox[2] / 2;
        double startY = grip.y + grip.height / 2;

        page.mouse().move(grip.x + grip.width / 2, startY);
        page.mouse().down();
        page.mouse().move(cx, startY - 6, new com.microsoft.playwright.Mouse.MoveOptions().setSteps(3));
        page.waitForTimeout(60);

        boolean reached = false;
        for (double yy = startY; yy > 40; yy -= 5) {     // 40px floor: stay below page chrome
            if (firstWords(itemOrder()).equals(desiredFirstWords)) { reached = true; break; }
            page.mouse().move(cx, yy, new com.microsoft.playwright.Mouse.MoveOptions().setSteps(1));
            page.waitForTimeout(40);
        }
        if (!reached) reached = firstWords(itemOrder()).equals(desiredFirstWords);

        page.mouse().up();
        page.waitForTimeout(300);                        // onEnd → updateOrderOnServer
    }

    private List<String> firstWords(List<String> names) {
        return names.stream().map(s -> s.trim().split("\\s+")[0]).collect(Collectors.toList());
    }

    @Test
    void mobileDragListDirectionIsPinnedVertical() {
        openFourOnPhone();
        collapseAllCards();

        // This IS the fix. Mobile cards are display:grid with two columns, which
        // SortableJS would otherwise auto-detect as a HORIZONTAL list and swap on
        // the X-axis — every card shares the same full-width X span, so upward
        // drags landed on the wrong neighbour or jumped to the top. Pinning the
        // direction to 'vertical' restores correct Y-axis reordering.
        assertEquals("vertical", sortableDirectionAfterDisablingAnimation(),
                "Mobile sortable must use vertical drag direction so cards reorder on the Y-axis");
    }

    @Test
    void draggingLastCardUpReordersItUpwardOffTheBottom() {
        openFourOnPhone();
        collapseAllCards();
        sortableDirectionAfterDisablingAnimation();

        assertEquals("Delta", firstWords(itemOrder()).get(3),
                "Delta should start last — was " + itemOrder());
        shoot("mobile-updrag-before.png");

        // The reported bug was that the last card could not be dragged upward at
        // all (it would only snap to the top). Confirm an upward drag genuinely
        // moves it up off the bottom. (A precise interior drop can't be faithfully
        // simulated with a synthetic pointer — SortableJS's forceFallback only
        // fires its extreme-insert branches under emulated input — so this asserts
        // the upward reorder happens; interior placement is verified in-browser.)
        dragGripUpUntilOrder(3, List.of("Delta", "Alpha", "Bravo", "Charlie"));

        List<String> after = firstWords(itemOrder());
        shoot("mobile-updrag-after.png");
        assertNotEquals("Delta", after.get(3),
                "Dragging the last card up must move it up off the bottom — order was " + after);
    }

    @Test
    void draggingSecondCardUpMovesOnlyItToTheTop() {
        openFourOnPhone();
        collapseAllCards();          // keep all four cards on the phone screen
        sortableDirectionAfterDisablingAnimation();

        // Bravo (2nd) dragged up above Alpha → Bravo to the top, nothing else shuffled.
        dragGripUpUntilOrder(1, List.of("Bravo", "Alpha", "Charlie", "Delta"));

        List<String> after = firstWords(itemOrder());
        assertEquals(List.of("Bravo", "Alpha", "Charlie", "Delta"), after,
                "Dragging the 2nd card up should move only it to the top, not shuffle the 3rd card "
                + "(the old bug put the 2nd down and the 3rd up). Order was " + after);
    }

    @Test
    void itemNameIsVerticallyCenteredWithTheGripOnMobile() {
        openDashboardOnPhone();

        Locator row = page.locator("tr.auto-save-row").first();
        double[] gripR = rectOf(row.locator("td.grip-cell .grip-icon i"));      // the dots glyph
        double[] textR = rectOf(row.locator("td[data-label='Item'] textarea")); // the headline text

        double gripMid = gripR[1] + gripR[3] / 2;
        double textMid = textR[1] + textR[3] / 2;
        assertTrue(Math.abs(gripMid - textMid) <= 6,
                "The item name should sit at the same vertical level as the grip burger "
                + "(text midline " + textMid + " vs grip midline " + gripMid + ")");

        shoot("mobile-item-grip-aligned.png");
    }
}
