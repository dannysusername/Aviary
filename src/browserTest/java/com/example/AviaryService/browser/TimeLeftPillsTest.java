package com.example.AviaryService.browser;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.StatusSheetItem;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.StatusSheetItemRepository;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.Media;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 011: Time Left as colored pills, no icons; "overdue" after an overdue
 * pill, once if both are; N/A when nothing is set; plain words in print.
 */
class TimeLeftPillsTest extends BrowserTestBase {

    @Autowired
    StatusSheetItemRepository statusSheetItems;

    private User user;
    private int order;

    private StatusSheetItem row(String name, String dueDate, String dueHours) {
        StatusSheetItem t = new StatusSheetItem();
        t.setUser(user);
        t.setAircraft(aircraftOf(user));
        t.setItem(name);
        t.setIsTitle(false);
        t.setStatusSheetOrder(order++);
        t.setDueDateDate(dueDate);
        t.setDueDateHours(dueHours);
        return statusSheetItems.save(t);
    }

    private Locator cellOf(StatusSheetItem i) {
        return page.locator("tr.auto-save-row[data-id='" + i.getId() + "'] .time-left");
    }

    private List<String> classesOfPills(Locator cell) {
        List<String> out = new ArrayList<>();
        for (Locator p : cell.locator(".tl-pill").all()) {
            String c = p.getAttribute("class");
            out.add(c.contains("tl-over") ? "over" : c.contains("tl-soon") ? "soon" : "ok");
        }
        return out;
    }

    private List<String> openWith(int width) {
        String username = seedUser("password");
        user = userRepository.findByUsername(username);
        Aircraft a = aircraftOf(user);
        a.setTimeInServiceHours(1000.0);
        aircraftRepository.save(a);
        List<String> errors = new ArrayList<>();
        page.onConsoleMessage(m -> { if ("error".equals(m.type())) errors.add(m.text()); });
        page.setViewportSize(width, 900);
        return errors;
    }

    private void login() {
        loginAs(user.getUsername(), "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);
    }

    @Test
    void everyCaseRendersAsAgreed() {
        List<String> errors = openWith(1400);
        LocalDate today = LocalDate.now();
        StatusSheetItem bothFine = row("both fine", today.plusDays(45).toString(), "1035.5");
        StatusSheetItem hoursOver = row("hours over", today.plusDays(45).toString(), "520");
        StatusSheetItem dateOver = row("date over", today.minusDays(12).toString(), "1035.5");
        StatusSheetItem bothOver = row("both over", today.minusDays(12).toString(), "520");
        StatusSheetItem dateOnly = row("date only", today.plusDays(45).toString(), null);
        StatusSheetItem hoursOnly = row("hours only", null, "520");
        StatusSheetItem soon = row("soon", today.plusDays(5).toString(), "1004");
        StatusSheetItem nothing = row("nothing", null, null);
        login();

        Locator c = cellOf(bothFine);
        assertThat(c.locator(".tl-pill")).hasText(new String[] {"45 d", "35.5 h"});
        assertEquals(List.of("ok", "ok"), classesOfPills(c));
        assertThat(c.locator(".tl-overdue")).hasCount(0);

        c = cellOf(hoursOver);
        assertThat(c.locator(".tl-pill")).hasText(new String[] {"45 d", "480 h"});
        assertEquals(List.of("ok", "over"), classesOfPills(c));
        assertThat(c.locator(".tl-overdue")).hasCount(1);
        assertThat(c.locator(".tl-pills")).hasText("45 d480 hoverdue");     // word right after the hours pill

        c = cellOf(dateOver);
        assertEquals(List.of("over", "ok"), classesOfPills(c));
        assertThat(c.locator(".tl-pills")).hasText("12 doverdue35.5 h");    // word right after the date pill

        c = cellOf(bothOver);
        assertEquals(List.of("over", "over"), classesOfPills(c));
        assertThat(c.locator(".tl-overdue")).hasCount(1);                  // once, after both
        assertThat(c.locator(".tl-pills")).hasText("12 d480 hoverdue");

        c = cellOf(dateOnly);
        assertThat(c.locator(".tl-pill")).hasText(new String[] {"45 d"});

        c = cellOf(hoursOnly);
        assertThat(c.locator(".tl-pill")).hasText(new String[] {"480 h"});
        assertThat(c.locator(".tl-overdue")).hasCount(1);

        assertEquals(List.of("soon", "soon"), classesOfPills(cellOf(soon)));

        assertThat(cellOf(nothing)).hasText("N/A");
        assertThat(cellOf(nothing).locator(".tl-pill")).hasCount(0);

        page.locator("#sortable-info-table").screenshot(new Locator.ScreenshotOptions()
            .setPath(java.nio.file.Paths.get("build/screenshots/time-left-pills.png")));

        // No icons anywhere in Time Left.
        assertEquals(0, page.locator(".time-left i, .time-left svg").count());

        // Print: plain words, no pills.
        page.emulateMedia(new Page.EmulateMediaOptions().setMedia(Media.PRINT));
        assertThat(cellOf(hoursOver).locator(".tl-pills")).isHidden();
        assertThat(cellOf(hoursOver).locator(".tl-plain")).isVisible();
        assertThat(cellOf(hoursOver).locator(".tl-plain")).hasText("45 days left · 480 hrs overdue");
        assertThat(cellOf(bothOver).locator(".tl-plain")).hasText("12 days overdue · 480 hrs overdue");
        page.emulateMedia(new Page.EmulateMediaOptions().setMedia(Media.SCREEN));
        if (!errors.isEmpty()) throw new AssertionError("Console errors: " + errors);
    }

    @Test
    void pillsFitAPhoneAndUpdateLive() {
        List<String> errors = openWith(360);
        LocalDate today = LocalDate.now();
        StatusSheetItem r = row("phone", today.plusDays(400).toString(), "520");
        login();

        Locator c = cellOf(r);
        assertThat(c.locator(".tl-pill")).hasCount(2);
        Object overflow = c.evaluate("el => el.scrollWidth > el.clientWidth + 1");
        assertEquals(false, overflow, "pills must not overflow the cell at 360px");
        Object pageOverflow = page.evaluate("() => document.documentElement.scrollWidth > document.documentElement.clientWidth");
        assertEquals(false, pageOverflow, "no sideways scroll at 360px");

        // Live: change the Due Date hours; the pill updates without reload.
        Locator dueHours = page.locator("tr.auto-save-row[data-id='" + r.getId() + "'] td:nth-child(6) input.extra-input[type='text']");
        dueHours.fill("1100");
        assertThat(c.locator(".tl-pill").nth(1)).hasText("100 h");
        assertTrue(c.locator(".tl-pill").nth(1).getAttribute("class").contains("tl-ok"));
        if (!errors.isEmpty()) throw new AssertionError("Console errors: " + errors);
    }
}
