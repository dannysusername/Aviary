package com.example.AviaryService.browser;

import com.example.AviaryService.entity.StatusSheetItem;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.StatusSheetItemRepository;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 010 in a real browser: the add row fills its Due Date from Last Done +
 * cycle before Add (behaviors 1-8), and the saved row matches.
 */
class AddRowAutoFillTest extends BrowserTestBase {

    @Autowired
    StatusSheetItemRepository statusSheetItems;

    private static final LocatorAssertions.HasValueOptions WAIT = new LocatorAssertions.HasValueOptions().setTimeout(5000);

    private Locator add;

    private List<String> open() {
        String username = seedUser("password");
        List<String> errors = new ArrayList<>();
        page.onConsoleMessage(m -> { if ("error".equals(m.type())) errors.add(m.text()); });
        page.setViewportSize(1400, 900);
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        add = page.locator("tr.add-row");
        return errors;
    }

    private Locator cell(String label) {
        return add.locator("td[data-label='" + label + "']");
    }

    private void openPart(String label, String type) {
        cell(label).locator(".trigger-dropdown").click();
        cell(label).locator(".add-type[data-type='" + type + "']").click();
    }

    @Test
    void addRowFillsBeforeAddAndSavedRowMatches() {
        List<String> errors = open();
        add.locator("textarea[name='item']").fill("Oil change");

        // 2: Last Done first, cycle afterwards.
        openPart("Last Done", "calendar");
        cell("Last Done").locator("input[type='date']").fill("2026-10-30");
        add.locator("input[name='cycleCalendarValue']").fill("1");
        add.locator("select[name='cycleCalendarUnit']").selectOption("MONTHS");

        // 1: Due Date fills (calendar opened by itself) before Add.
        Locator dueDate = cell("Due Date").locator("input[type='date']");
        assertThat(dueDate).hasValue("2026-11-30", WAIT);

        // Hours too, and 6: 25 -> 25.0 on leaving the box.
        add.locator("input[name='cycleHours']").fill("100");
        openPart("Last Done", "clock");
        Locator lastHours = cell("Last Done").locator("input.extra-input[type='text']");
        lastHours.fill("25");
        add.locator("textarea[name='item']").click();
        assertThat(lastHours).hasValue("25.0");
        assertThat(cell("Due Date").locator("input.extra-input[type='text']")).hasValue("125.0", WAIT);

        // 8: Add -> saved row matches, add row clears.
        add.locator("button.add-button").click();
        page.waitForLoadState(LoadState.NETWORKIDLE);
        Locator saved = page.locator("tr.auto-save-row").last();
        assertThat(saved.locator("td:nth-child(6) input[type='date']")).hasValue("2026-11-30", WAIT);
        assertThat(saved.locator("td:nth-child(6) input.extra-input[type='text']")).hasValue("125.0");
        assertThat(add.locator("td[data-label='Due Date'] input.extra-input")).hasCount(0);
        assertThat(add.locator("textarea[name='item']")).hasValue("");

        StatusSheetItem row = statusSheetItems.findAll().stream()
            .filter(i -> "Oil change".equals(i.getItem())).findFirst().orElseThrow();
        assertTrue(row.getDueDateAuto(), "auto-filled in the add row stays auto after Add");
        if (!errors.isEmpty()) throw new AssertionError("Console errors: " + errors);
    }

    @Test
    void typedAndRemovedDueDatesAreRespectedAndLastDoneClears() {
        List<String> errors = open();
        add.locator("textarea[name='item']").fill("Annual");
        add.locator("input[name='cycleCalendarValue']").fill("1");
        add.locator("select[name='cycleCalendarUnit']").selectOption("MONTHS");
        openPart("Last Done", "calendar");
        Locator lastDate = cell("Last Done").locator("input[type='date']");
        lastDate.fill("2026-10-30");
        Locator dueDate = cell("Due Date").locator("input[type='date']");
        assertThat(dueDate).hasValue("2026-11-30", WAIT);

        // 5: clearing Last Done clears the auto Due Date.
        lastDate.fill("");
        assertThat(dueDate).hasCount(0, new LocatorAssertions.HasCountOptions().setTimeout(5000));

        // 3: typed Due Date stays when Last Done changes.
        lastDate.fill("2026-10-30");
        assertThat(dueDate).hasValue("2026-11-30", WAIT);
        dueDate.fill("2027-06-01");
        page.waitForTimeout(600);
        lastDate.fill("2026-12-05");
        page.waitForTimeout(800);
        assertThat(dueDate).hasValue("2027-06-01");

        // 4: Remove stays removed until Last Done or the cycle changes.
        cell("Due Date").locator(".trigger-dropdown").click();
        Locator remove = cell("Due Date").locator(".add-type[data-type='calendar']");
        remove.click();
        remove.click();                                    // confirm "Remove?"
        page.waitForTimeout(800);
        assertThat(dueDate).hasCount(0);
        add.locator("textarea[name='item']").fill("Annual inspection");   // unrelated change
        page.waitForTimeout(800);
        assertThat(dueDate).hasCount(0);
        lastDate.fill("2026-12-10");
        assertThat(dueDate).hasValue("2027-01-10", WAIT);
        if (!errors.isEmpty()) throw new AssertionError("Console errors: " + errors);
    }

    @Test
    void futureLastDoneShowsANoteButAddStillWorks() {
        List<String> errors = open();
        add.locator("textarea[name='item']").fill("ELT");
        add.locator("input[name='cycleCalendarValue']").fill("12");
        add.locator("select[name='cycleCalendarUnit']").selectOption("MONTHS");
        openPart("Last Done", "calendar");
        String future = java.time.LocalDate.now().plusYears(1).toString();
        cell("Last Done").locator("input[type='date']").fill(future);
        assertThat(cell("Last Done").locator(".row-warning")).hasText("Last Done is in the future. Check the date.");
        int before = statusSheetItems.findAll().size();
        add.locator("button.add-button").click();
        page.waitForLoadState(LoadState.NETWORKIDLE);
        page.waitForTimeout(500);
        assertEquals(before + 1, statusSheetItems.findAll().size(), "note does not block Add");
        assertThat(cell("Last Done").locator(".row-warning")).hasCount(0);
        if (!errors.isEmpty()) throw new AssertionError("Console errors: " + errors);
    }

    @Test
    void titleModeFillsNothing() {
        List<String> errors = open();
        add.locator("input[name='cycleCalendarValue']").fill("1");
        add.locator("select[name='cycleCalendarUnit']").selectOption("MONTHS");
        openPart("Last Done", "calendar");
        cell("Last Done").locator("input[type='date']").fill("2026-10-30");
        assertThat(cell("Due Date").locator("input[type='date']")).hasValue("2026-11-30", WAIT);
        cell("Due Date").locator(".trigger-dropdown").click();
        Locator remove = cell("Due Date").locator(".add-type[data-type='calendar']");
        remove.click();
        remove.click();
        page.waitForTimeout(600);

        page.locator(".row-type-option[data-type='title']").click();
        // Title mode hides Last Done / Due Date, so nothing can fill or show a note.
        assertThat(cell("Last Done").locator("input[type='date']")).isHidden();
        page.evaluate("() => { const i = document.querySelector('.add-row td[data-label=\"Last Done\"] input[type=date]');"
            + " i.value = '2026-11-15'; i.dispatchEvent(new Event('input', {bubbles: true})); }");
        page.waitForTimeout(800);
        assertThat(cell("Due Date").locator("input[type='date']")).hasCount(0);
        assertThat(cell("Last Done").locator(".row-warning")).hasCount(0);
        if (!errors.isEmpty()) throw new AssertionError("Console errors: " + errors);
    }
}
