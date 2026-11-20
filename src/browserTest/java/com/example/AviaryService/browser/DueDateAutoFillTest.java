package com.example.AviaryService.browser;

import com.example.AviaryService.entity.StatusSheetItem;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.StatusSheetItemRepository;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Spec 007 in a real browser: after the owner enters Last Done, the
 * auto-filled Due Date appears on screen without reloading the page, and
 * hours tidy to the 25.0 format when the owner leaves the box.
 */
class DueDateAutoFillTest extends BrowserTestBase {

    @Autowired
    StatusSheetItemRepository statusSheetItems;

    private StatusSheetItem seed(String username, Integer calValue, String calUnit, Double cycleHours,
                                 String lastDoneDate, String lastDoneHours) {
        User user = userRepository.findByUsername(username);
        StatusSheetItem t = new StatusSheetItem();
        t.setUser(user);
        t.setAircraft(aircraftOf(user));
        t.setItem("Oil change");
        t.setIsTitle(false);
        t.setStatusSheetOrder(0);
        t.setCycleCalendarValue(calValue);
        t.setCycleCalendarUnit(calUnit);
        t.setCycleHours(cycleHours);
        t.setLastDoneDate(lastDoneDate);
        t.setLastDoneHours(lastDoneHours);
        return statusSheetItems.save(t);
    }

    private List<String> open(String username) {
        List<String> errors = new ArrayList<>();
        page.onConsoleMessage(m -> { if ("error".equals(m.type())) errors.add(m.text()); });
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);
        return errors;
    }

    @Test
    void dueDateAppearsRightAfterLastDoneWithoutReload() {
        String username = seedUser("password");
        StatusSheetItem item = seed(username, 1, "MONTHS", null, "2026-09-15", null);
        List<String> errors = open(username);

        Locator row = page.locator("tr.auto-save-row[data-id='" + item.getId() + "']");
        Locator lastDone = row.locator("td:nth-child(5) input[type='date']");
        lastDone.fill("2026-10-30");

        // No reload: the Due Date shows up in place once the save returns.
        Locator due = row.locator("td:nth-child(6) input[type='date']");
        assertThat(due).hasValue("2026-11-30", new com.microsoft.playwright.assertions.LocatorAssertions.HasValueOptions().setTimeout(5000));
        if (!errors.isEmpty()) throw new AssertionError("Console errors: " + errors);
    }

    @Test
    void hoursTidyOnBlurAndDueHoursFill() {
        String username = seedUser("password");
        StatusSheetItem item = seed(username, null, null, 100.0, null, "900");
        List<String> errors = open(username);

        Locator row = page.locator("tr.auto-save-row[data-id='" + item.getId() + "']");
        Locator lastHours = row.locator("td:nth-child(5) input.extra-input[type='text']");
        assertThat(lastHours).hasValue("900.0");   // stored "900", shown with a decimal

        lastHours.fill("950");
        row.locator("td:nth-child(2)").click();     // leave the box
        assertThat(lastHours).hasValue("950.0");

        Locator dueHours = row.locator("td:nth-child(6) input.extra-input[type='text']");
        assertThat(dueHours).hasValue("1050.0", new com.microsoft.playwright.assertions.LocatorAssertions.HasValueOptions().setTimeout(5000));
        if (!errors.isEmpty()) throw new AssertionError("Console errors: " + errors);
    }

    // Reported bug: 12 mo -> 1 mo -> delete the number -> Remove the Due Date
    // calendar. The old Due Date used to reappear straight away.
    @Test
    void removedDueDateStaysRemovedWhenCycleNumberIsDeleted() {
        String username = seedUser("password");
        StatusSheetItem item = seed(username, 12, "MONTHS", null, "2020-12-01", null);
        List<String> errors = open(username);

        Locator row = page.locator("tr.auto-save-row[data-id='" + item.getId() + "']");
        Locator due = row.locator("td:nth-child(6) input[type='date']");
        Locator cycleNumber = row.locator("input[name='cycleCalendarValue']");

        // Setting Last Done again fills 12 months later.
        row.locator("td:nth-child(5) input[type='date']").fill("2020-12-01");
        cycleNumber.fill("12");
        assertThat(due).hasValue("2021-12-01", new com.microsoft.playwright.assertions.LocatorAssertions.HasValueOptions().setTimeout(5000));

        cycleNumber.fill("1");
        assertThat(due).hasValue("2021-01-01", new com.microsoft.playwright.assertions.LocatorAssertions.HasValueOptions().setTimeout(5000));

        cycleNumber.fill("");
        page.waitForTimeout(1200);                       // let that save finish

        Locator dueCell = row.locator("td:nth-child(6)");
        dueCell.locator(".trigger-dropdown").click();
        Locator remove = dueCell.locator(".add-type[data-type='calendar']");
        remove.click();                                   // "Remove?" (has a value)
        remove.click();                                   // confirm
        page.waitForTimeout(1500);                       // save + response

        assertThat(due).hasCount(0);
        if (!errors.isEmpty()) throw new AssertionError("Console errors: " + errors);
    }

    @Test
    void rollForwardBoxOffersResetToCycleForATypedDueDate() {
        String username = seedUser("password");
        StatusSheetItem item = seed(username, 1, "MONTHS", null, "2020-12-01", null);
        List<String> errors = open(username);

        Locator row = page.locator("tr.auto-save-row[data-id='" + item.getId() + "']");
        Locator due = row.locator("td:nth-child(6) input[type='date']");
        row.locator("td:nth-child(5) input[type='date']").fill("2020-12-01");
        assertThat(due).hasValue("2021-01-01", new com.microsoft.playwright.assertions.LocatorAssertions.HasValueOptions().setTimeout(5000));

        // Owner types their own Due Date, then opens the box right away
        // (before the 500ms save would have gone out on its own).
        due.fill("2021-03-15");
        row.locator(".complete-btn").click();

        Locator reset = page.locator(".choice-box button[data-choice='reset']");
        assertThat(reset).hasText("Reset to cycle (01/01/2021)");
        assertThat(reset).isEnabled();
        assertThat(page.locator(".choice-box button[data-choice='complete']")).isEnabled();
        reset.click();

        assertThat(due).hasValue("2021-01-01", new com.microsoft.playwright.assertions.LocatorAssertions.HasValueOptions().setTimeout(5000));
        assertThat(row.locator("td:nth-child(5) input[type='date']")).hasValue("2020-12-01");

        // Now it matches: Reset is grayed out with the reason.
        row.locator(".complete-btn").click();
        assertThat(reset).isDisabled();
        assertThat(page.locator(".choice-box")).containsText("Due Date already matches the cycle");
        page.keyboard().press("Escape");
        if (!errors.isEmpty()) throw new AssertionError("Console errors: " + errors);
    }
}
