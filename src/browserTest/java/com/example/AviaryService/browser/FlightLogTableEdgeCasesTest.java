package com.example.AviaryService.browser;

import com.example.AviaryService.entity.FlightLog;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.FlightLogRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Edge-case rendering tests for the Flight Log table on the dashboard.
 *
 * Flight Log rows render with both a print-only span (for the print
 * stylesheet) and a readonly input (visible on screen). The input is
 * what users see, so most assertions go against input.value.
 *
 * Add a test here every time the Flight Log UI is fixed or extended.
 */
class FlightLogTableEdgeCasesTest extends BrowserTestBase {

    @Autowired
    UserRepository users;

    @Autowired
    FlightLogRepository flightLogs;

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

    private FlightLog saveLog(User user, String from, String to,
                              Double hobbsOut, Double hobbsIn,
                              Double tachOut, Double tachIn) {
        FlightLog log = new FlightLog();
        log.setUser(user);
        log.setFromAirport(from);
        log.setToAirport(to);
        log.setHobbsOut(hobbsOut);
        log.setHobbsIn(hobbsIn);
        log.setTachOut(tachOut);
        log.setTachIn(tachIn);
        return flightLogs.save(log);
    }

    @Test
    void emptyLogbookRendersNoLogRows() {
        String username = seedUser("password");
        List<String> errors = attachConsoleErrorListener();

        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        // Only the add-log-row is in the tbody for a user with no logs.
        assertThat(page.locator("tbody#logbook-body tr.log-row")).hasCount(0);
        assertThat(page.locator("tbody#logbook-body tr.add-log-row")).hasCount(1);

        assertNoConsoleErrors(errors);
    }

    @Test
    void typicalLogRendersAllCellsWithDecimalHours() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        FlightLog log = saveLog(u, "KAAA", "KBBB", 100.0, 101.5, 50.0, 51.5);

        List<String> errors = attachConsoleErrorListener();
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        var row = page.locator("tbody#logbook-body tr.log-row[data-id='" + log.getId() + "']");
        assertThat(row).hasCount(1);
        assertThat(row.locator("input[name='fromAirport']")).hasValue("KAAA");
        assertThat(row.locator("input[name='toAirport']")).hasValue("KBBB");
        assertThat(row.locator("input[name='hobbsOut']")).hasValue("100.0");
        assertThat(row.locator("input[name='hobbsIn']")).hasValue("101.5");
        assertThat(row.locator("input[name='tachOut']")).hasValue("50.0");
        assertThat(row.locator("input[name='tachIn']")).hasValue("51.5");

        // Inputs must stay readonly — the row table is display-only; users
        // edit via the add-row + delete only.
        assertThat(row.locator("input[name='fromAirport']")).hasAttribute("readonly", "");

        assertNoConsoleErrors(errors);
    }

    @Test
    void nullAirportsAndHoursRenderEmptyWithoutBreaking() {
        // FlightLog columns are all nullable; render must tolerate it.
        String username = seedUser("password");
        User u = users.findByUsername(username);
        FlightLog log = saveLog(u, null, null, null, null, null, null);

        List<String> errors = attachConsoleErrorListener();
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        var row = page.locator("tbody#logbook-body tr.log-row[data-id='" + log.getId() + "']");
        assertThat(row).hasCount(1);
        assertThat(row.locator("input[name='fromAirport']")).hasValue("");
        assertThat(row.locator("input[name='toAirport']")).hasValue("");
        assertThat(row.locator("input[name='hobbsOut']")).hasValue("");
        assertThat(row.locator("input[name='hobbsIn']")).hasValue("");
        assertThat(row.locator("input[name='tachOut']")).hasValue("");
        assertThat(row.locator("input[name='tachIn']")).hasValue("");

        assertNoConsoleErrors(errors);
    }

    @Test
    void zeroDurationLegRendersWithZeroValues() {
        // Edge case: a log where the meter values are all zero, e.g. a
        // brand-new airframe getting its first ground-test entry.
        String username = seedUser("password");
        User u = users.findByUsername(username);
        FlightLog log = saveLog(u, "KAAA", "KAAA", 0.0, 0.0, 0.0, 0.0);

        List<String> errors = attachConsoleErrorListener();
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        var row = page.locator("tbody#logbook-body tr.log-row[data-id='" + log.getId() + "']");
        assertThat(row.locator("input[name='hobbsOut']")).hasValue("0.0");
        assertThat(row.locator("input[name='hobbsIn']")).hasValue("0.0");
        assertThat(row.locator("input[name='tachOut']")).hasValue("0.0");
        assertThat(row.locator("input[name='tachIn']")).hasValue("0.0");

        assertNoConsoleErrors(errors);
    }

    @Test
    void veryLargeHoursValueRenders() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        FlightLog log = saveLog(u, "KAAA", "KBBB", 99998.5, 99999.9, 99998.5, 99999.9);

        List<String> errors = attachConsoleErrorListener();
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        var row = page.locator("tbody#logbook-body tr.log-row[data-id='" + log.getId() + "']");
        assertThat(row.locator("input[name='hobbsIn']")).hasValue("99999.9");
        assertThat(row.locator("input[name='tachIn']")).hasValue("99999.9");

        assertNoConsoleErrors(errors);
    }

    @Test
    void specialCharsInAirportFieldsAreEscapedNotInterpreted() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        // Airport fields are free-text in the UI — injection-shaped input
        // must round-trip as plain text, not interpreted markup.
        String inj = "<script>window.__pwnedLog=1</script>";
        FlightLog log = saveLog(u, inj, "K\"B&B'C", 1.0, 2.0, 1.0, 2.0);

        List<String> errors = attachConsoleErrorListener();
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        var row = page.locator("tbody#logbook-body tr.log-row[data-id='" + log.getId() + "']");
        assertThat(row.locator("input[name='fromAirport']")).hasValue(inj);
        assertThat(row.locator("input[name='toAirport']")).hasValue("K\"B&B'C");

        Object pwned = page.evaluate("() => window.__pwnedLog");
        if (pwned != null) {
            throw new AssertionError("XSS in fromAirport: window.__pwnedLog was set");
        }

        assertNoConsoleErrors(errors);
    }

    @Test
    void multipleLogRowsRenderInInsertionOrder() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        FlightLog l1 = saveLog(u, "K001", "K002", 1.0, 2.0, 1.0, 2.0);
        FlightLog l2 = saveLog(u, "K003", "K004", 2.0, 3.0, 2.0, 3.0);
        FlightLog l3 = saveLog(u, "K005", "K006", 3.0, 4.0, 3.0, 4.0);

        List<String> errors = attachConsoleErrorListener();
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        var rows = page.locator("tbody#logbook-body tr.log-row");
        assertThat(rows).hasCount(3);
        // Insertion order = primary-key ascending = JpaRepository default.
        assertThat(rows.nth(0).locator("input[name='fromAirport']")).hasValue("K001");
        assertThat(rows.nth(1).locator("input[name='fromAirport']")).hasValue("K003");
        assertThat(rows.nth(2).locator("input[name='fromAirport']")).hasValue("K005");
        // Sanity: ids match.
        assertThat(rows.nth(0)).hasAttribute("data-id", String.valueOf(l1.getId()));
        assertThat(rows.nth(1)).hasAttribute("data-id", String.valueOf(l2.getId()));
        assertThat(rows.nth(2)).hasAttribute("data-id", String.valueOf(l3.getId()));

        assertNoConsoleErrors(errors);
    }
}
