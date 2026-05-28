package com.example.AviaryService.browser;

import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Edge-case rendering tests for the Service Timeline table.
 *
 * The timeline tbody contains both title rows (.title-row) and regular
 * item rows (.auto-save-row). Each row carries data-id; cells have
 * data-label="..." so we can locate by column without relying on
 * positional indexing that breaks when the schema shifts.
 *
 * Add a test here every time the timeline table is fixed or extended.
 */
class ServiceTimelineTableEdgeCasesTest extends BrowserTestBase {

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

    private void assertNoConsoleErrors(List<String> errors) {
        if (!errors.isEmpty()) throw new AssertionError("Console errors: " + errors);
    }

    /** Build + save a regular (non-title) timeline row. */
    private ServiceTimeline saveItem(User user, int order, String item, String description,
                                     String lastDone, String dueDate, String timeLeft) {
        ServiceTimeline t = new ServiceTimeline();
        t.setUser(user);
        t.setItem(item);
        t.setIsTitle(false);
        t.setTimelineOrder(order);
        t.setDescription(description);
        t.setLastDone(lastDone);
        t.setDueDate(dueDate);
        t.setTimeLeft(timeLeft);
        return timelines.save(t);
    }

    @Test
    void emptyTimelineRendersTableWithNoRows() {
        String username = seedUser("password");
        List<String> errors = attachConsoleErrorListener();

        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        // Sortable tbody exists but has zero data rows (only the add-row in a
        // sibling tbody). No console errors during render of an empty table.
        assertThat(page.locator("tbody.sortable .auto-save-row")).hasCount(0);
        assertThat(page.locator("tbody.sortable .title-row")).hasCount(0);

        assertNoConsoleErrors(errors);
    }

    @Test
    void titleRowRendersWithItemTextInTitleCell() {
        String username = seedUser("password");
        User u = users.findByUsername(username);

        ServiceTimeline title = new ServiceTimeline();
        title.setUser(u);
        title.setItem("100-Hour Inspections");
        title.setIsTitle(true);
        title.setTimelineOrder(1);
        timelines.save(title);

        List<String> errors = attachConsoleErrorListener();
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        // Title row uses td.title-cell, not the regular item textarea.
        assertThat(page.locator("tbody.sortable tr.title-row")).hasCount(1);
        assertThat(page.locator("tbody.sortable tr.title-row td.title-cell"))
                .hasText("100-Hour Inspections");
        // Regular-row count is zero.
        assertThat(page.locator("tbody.sortable tr.auto-save-row")).hasCount(0);

        assertNoConsoleErrors(errors);
    }

    @Test
    void regularRowRendersItemAndDescriptionAndDateFields() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        ServiceTimeline t = saveItem(u, 1, "Oil change", "Inspect",
                "2025-10-01", "2025-12-01", "61 days");

        List<String> errors = attachConsoleErrorListener();
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        // Locate the exact row by its data-id so column assertions are stable.
        var row = page.locator("tr.auto-save-row[data-id='" + t.getId() + "']");
        assertThat(row).hasCount(1);

        // Item lives in the editable textarea + a print-only span.
        assertThat(row.locator("textarea[name='item']")).hasValue("Oil change");
        // Description is stored in a hidden input — that's what controllers read.
        assertThat(row.locator("input[name='description'][type='hidden']"))
                .hasValue("Inspect");
        // Last Done + Due Date are rendered into .print-only spans. Those are
        // display:none on screen, so Playwright's innerText-based hasText()
        // would see empty. textContent() reads the DOM regardless of CSS.
        String lastDone = row.locator("td[data-label='Last Done'] span.print-only")
                .textContent();
        if (!"2025-10-01".equals(lastDone)) {
            throw new AssertionError("Last Done textContent was '" + lastDone + "'");
        }
        String dueDate = row.locator("td[data-label='Due Date'] span.print-only")
                .textContent();
        if (!"2025-12-01".equals(dueDate)) {
            throw new AssertionError("Due Date textContent was '" + dueDate + "'");
        }
        // .time-left is recomputed by dashboard.js (calculateTimeLeft) from
        // the row's dueDate on page load — the seeded string is overwritten.
        // Assert the JS produced *something* date-shaped, not a literal value
        // (date-relative strings are brittle as the calendar advances).
        String tl = row.locator("td[data-label='Time Left'] div.time-left").textContent();
        if (tl == null || tl.isBlank()) {
            throw new AssertionError("Time Left should have been computed by JS, was '" + tl + "'");
        }
        if (!tl.contains("day") && !tl.contains("hour") && !tl.contains("today") && !tl.contains("N/A")) {
            throw new AssertionError("Time Left looks unexpected: '" + tl + "'");
        }

        assertNoConsoleErrors(errors);
    }

    @Test
    void emptyDescriptionRendersWithoutFallingBackToHtmlDefault() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        ServiceTimeline t = saveItem(u, 1, "Item with no description", "",
                "2025-10-01", "2025-12-01", "");

        List<String> errors = attachConsoleErrorListener();
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        var row = page.locator("tr.auto-save-row[data-id='" + t.getId() + "']");
        // Empty description: the hidden input's value should be empty, not "Inspect".
        // (We don't assert on .time-left here — JS recomputes it from dueDate,
        // see regularRowRendersItemAndDescriptionAndDateFields.)
        assertThat(row.locator("input[name='description'][type='hidden']"))
                .hasValue("");

        assertNoConsoleErrors(errors);
    }

    @Test
    void veryLongItemTextRendersWithoutBreakingLayout() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        // Item is a String column → Hibernate default VARCHAR(255). Pick a
        // length that's "very long" (exercises wrapping/layout) but valid.
        // If the column is ever widened, bump this; if it shrinks, this test
        // will fail loudly which is the correct behaviour.
        String longText = "X".repeat(240);
        ServiceTimeline t = saveItem(u, 1, longText, "Inspect",
                "2025-10-01", "2025-12-01", "61 days");

        List<String> errors = attachConsoleErrorListener();
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        var row = page.locator("tr.auto-save-row[data-id='" + t.getId() + "']");
        assertThat(row.locator("textarea[name='item']")).hasValue(longText);

        assertNoConsoleErrors(errors);
    }

    @Test
    void specialCharsInItemAndDescriptionAreEscapedNotInterpreted() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        // If Thymeleaf forgets to escape, this string injects markup.
        String injection = "<script>window.__pwned=1</script> & \"quote\" 'apos'";
        ServiceTimeline t = saveItem(u, 1, injection, injection,
                "2025-10-01", "2025-12-01", "61 days");

        List<String> errors = attachConsoleErrorListener();
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        var row = page.locator("tr.auto-save-row[data-id='" + t.getId() + "']");
        // String round-trips into both the textarea (text content) and the
        // hidden description input (attribute value).
        assertThat(row.locator("textarea[name='item']")).hasValue(injection);
        assertThat(row.locator("input[name='description'][type='hidden']"))
                .hasValue(injection);
        // Critical: the injected script must NOT have executed.
        Object pwned = page.evaluate("() => window.__pwned");
        if (pwned != null) {
            throw new AssertionError("XSS in item/description: window.__pwned was set");
        }

        assertNoConsoleErrors(errors);
    }

    @Test
    void multipleRowsRenderInTimelineOrder() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        // Insert out of order to make sure the sort actually fires.
        saveItem(u, 3, "Third",  "Inspect", "2025-01-03", "2025-12-03", "1 day");
        saveItem(u, 1, "First",  "Inspect", "2025-01-01", "2025-12-01", "1 day");
        saveItem(u, 2, "Second", "Inspect", "2025-01-02", "2025-12-02", "1 day");

        List<String> errors = attachConsoleErrorListener();
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        var itemTextareas = page.locator("tr.auto-save-row textarea[name='item']");
        assertThat(itemTextareas).hasCount(3);
        // findByUserOrderByTimelineOrderAsc → First, Second, Third.
        assertThat(itemTextareas.nth(0)).hasValue("First");
        assertThat(itemTextareas.nth(1)).hasValue("Second");
        assertThat(itemTextareas.nth(2)).hasValue("Third");

        assertNoConsoleErrors(errors);
    }
}
